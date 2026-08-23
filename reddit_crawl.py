#!/usr/bin/env python3
"""Crawl Reddit for Starlink-theft threads worth replying to.

Runs a keyword sweep across a configurable set of queries and subreddits,
scores every hit for how well it fits an anti-theft app, and writes a ranked
Markdown report plus the raw JSON.

Auth is optional:
  * OAuth (recommended) - set REDDIT_CLIENT_ID / REDDIT_CLIENT_SECRET, plus
    REDDIT_USERNAME / REDDIT_PASSWORD for a script-type app. Higher rate limit,
    stable results.
  * Anonymous - no credentials; falls back to the public .json endpoints. Works
    from a normal machine, but Reddit throttles hard and blocks datacenter IPs.

  python reddit_crawl.py --since-days 365 --out reports/
"""

from __future__ import annotations

import argparse
import json
import math
import os
import pathlib
import re
import sys
import time
from datetime import datetime, timezone

import requests

ROOT = pathlib.Path(__file__).resolve().parent
DEFAULT_CONFIG = ROOT / "config" / "targets.json"
UA = os.environ.get(
    "REDDIT_USER_AGENT", "starlink-anti-theft-lead-finder/1.0 (by /u/your_username)"
)


# --------------------------------------------------------------------------- #
# Reddit client
# --------------------------------------------------------------------------- #
class Reddit:
    """Minimal read-only Reddit client with OAuth and anonymous modes."""

    def __init__(self, verbose: bool = False):
        self.session = requests.Session()
        self.session.headers["User-Agent"] = UA
        self.verbose = verbose
        self.token = None
        self.ok_requests = 0
        self.base = "https://www.reddit.com"
        self.pause = 1.2
        self._authenticate()

    def _authenticate(self) -> None:
        cid = os.environ.get("REDDIT_CLIENT_ID")
        secret = os.environ.get("REDDIT_CLIENT_SECRET")
        if not (cid and secret):
            print("[auth] no credentials found - using anonymous public JSON", file=sys.stderr)
            return

        user = os.environ.get("REDDIT_USERNAME")
        pw = os.environ.get("REDDIT_PASSWORD")
        if user and pw:
            data = {"grant_type": "password", "username": user, "password": pw}
        else:
            data = {"grant_type": "client_credentials"}

        resp = self.session.post(
            "https://www.reddit.com/api/v1/access_token",
            auth=(cid, secret),
            data=data,
            timeout=30,
        )
        if resp.status_code != 200:
            print(
                f"[auth] token request failed ({resp.status_code}) - falling back to anonymous",
                file=sys.stderr,
            )
            return

        self.token = resp.json()["access_token"]
        self.session.headers["Authorization"] = f"bearer {self.token}"
        self.base = "https://oauth.reddit.com"
        self.pause = 0.6
        print("[auth] authenticated via OAuth", file=sys.stderr)

    def _get(self, path: str, params: dict) -> dict | None:
        url = f"{self.base}{path}"
        if not self.token:
            url += ".json"
        for attempt in range(5):
            try:
                resp = self.session.get(url, params=params, timeout=30)
            except requests.RequestException as exc:
                print(f"[warn] {path}: {exc}", file=sys.stderr)
                time.sleep(2 ** attempt)
                continue

            if resp.status_code == 200:
                self.ok_requests += 1
                time.sleep(self.pause)
                try:
                    return resp.json()
                except ValueError:
                    print(f"[warn] {path}: non-JSON response", file=sys.stderr)
                    return None
            if resp.status_code in (429, 500, 502, 503):
                wait = 2 ** attempt * 3
                print(f"[warn] {path}: HTTP {resp.status_code}, retrying in {wait}s", file=sys.stderr)
                time.sleep(wait)
                continue
            # 403/404: private, banned, or non-existent subreddit - skip quietly.
            if self.verbose:
                print(f"[skip] {path}: HTTP {resp.status_code}", file=sys.stderr)
            return None
        return None

    def reachable(self) -> bool:
        """Reddit serves a block page to datacenter IPs; find out before crawling."""
        try:
            resp = self.session.get(f"{self.base}/r/Starlink/new" + ("" if self.token else ".json"),
                                    params={"limit": 1, "raw_json": 1}, timeout=30)
        except requests.RequestException:
            return False
        return resp.status_code == 200

    def search(
        self, query: str, subreddit: str | None = None, sort: str = "relevance",
        time_filter: str = "all", limit: int = 100,
    ) -> list[dict]:
        path = f"/r/{subreddit}/search" if subreddit else "/search"
        params = {
            "q": query,
            "sort": sort,
            "t": time_filter,
            "limit": min(limit, 100),
            "type": "link",
            "raw_json": 1,
        }
        if subreddit:
            params["restrict_sr"] = "on"

        out: list[dict] = []
        after = None
        while len(out) < limit:
            if after:
                params["after"] = after
            payload = self._get(path, params)
            if not payload or "data" not in payload:
                break
            children = payload["data"].get("children", [])
            out.extend(c["data"] for c in children if c.get("kind") == "t3")
            after = payload["data"].get("after")
            if not after or not children:
                break
        return out[:limit]

    def listing(self, subreddit: str, kind: str = "new", limit: int = 100) -> list[dict]:
        payload = self._get(f"/r/{subreddit}/{kind}", {"limit": min(limit, 100), "raw_json": 1})
        if not payload or "data" not in payload:
            return []
        return [c["data"] for c in payload["data"].get("children", []) if c.get("kind") == "t3"]


# --------------------------------------------------------------------------- #
# Arctic Shift client (fallback data source)
# --------------------------------------------------------------------------- #
class ArcticShift:
    """Reads the Arctic Shift public Reddit archive.

    Reddit blocks datacenter IPs outright - every reddit.com path, including
    oauth.reddit.com, returns a block page - so from a cloud VM this is the
    only route to the data. Two differences from the Reddit API matter: there
    is no site-wide search (every query must name a subreddit), and the archive
    stores each post as first captured, so scores and comment counts are lower
    bounds rather than live values.
    """

    BASE = "https://arctic-shift.photon-reddit.com/api/posts/search"

    def __init__(self, pause: float = 4.0, attempts: int = 10, verbose: bool = False):
        self.session = requests.Session()
        self.session.headers["User-Agent"] = UA
        self.pause = pause
        self.attempts = attempts
        self.verbose = verbose
        self.ok_requests = 0
        self.timeouts = 0
        self.sub_hits: dict[str, int] = {}

    def search_sub(self, subreddit: str, field: str, term: str,
                   after: float | None = None, limit: int = 100) -> list[dict]:
        params = {"subreddit": subreddit, field: term, "limit": min(limit, 100), "sort": "desc"}
        if after:
            params["after"] = int(after)

        for attempt in range(self.attempts):
            try:
                resp = self.session.get(self.BASE, params=params, timeout=90)
            except requests.RequestException as exc:
                print(f"[warn] {subreddit}/{field}={term}: {exc}", file=sys.stderr)
                time.sleep(2 ** attempt)
                continue

            if resp.status_code == 200:
                self.ok_requests += 1
                time.sleep(self.pause)
                data = resp.json().get("data") or []
                self.sub_hits[subreddit] = self.sub_hits.get(subreddit, 0) + len(data)
                return data

            # 422 is a server-side query timeout, not a quota: roughly one
            # request in three succeeds no matter how long you wait, so retry
            # steadily rather than backing off into a stall. A real 429 does
            # want exponential backoff.
            if resp.status_code == 422:
                self.timeouts += 1
                if self.verbose:
                    print(f"[warn] {subreddit}/{field}={term}: 422 timeout, retrying",
                          file=sys.stderr)
                time.sleep(self.pause)
                continue

            if resp.status_code in (429, 500, 502, 503):
                wait = self.pause * (2 ** attempt)
                print(f"[warn] {subreddit}/{field}={term}: HTTP {resp.status_code}, "
                      f"waiting {wait:.0f}s", file=sys.stderr)
                time.sleep(wait)
                continue

            print(f"[skip] {subreddit}/{field}={term}: HTTP {resp.status_code}", file=sys.stderr)
            return []

        print(f"[fail] {subreddit}/{field}={term}: gave up after retries", file=sys.stderr)
        return []


# --------------------------------------------------------------------------- #
# Scoring
# --------------------------------------------------------------------------- #
# How close a theft signal must be to a Starlink mention to count as on-topic.
PROXIMITY_CHARS = 220
SUBJECT_RE = re.compile(r"\b(starlink|dishy|dish)\b")

VICTIM_RE = re.compile(
    r"(stole (my|our)|someone stole|thieves took|"
    r"\b(was|were|got|has been|have been|had been)\s+(stolen|taken|ripped off)\b|"
    r"had\s+(my|our)\b[^.!?\n]{0,40}\bstolen|"
    r"filed a police report|police report (for|about)|"
    r"reported (it|the theft|this) to (starlink|support|the police)|"
    r"(my|our)\s+(starlink|dish|dishy|mini)[^.!?\n]{0,40}\b(gone|missing|disappeared))"
)

# "what would you do if yours was stolen" is a hypothetical, not a theft. It
# trips the past-tense pattern above, so it gets vetoed unless the post also
# carries a concrete aftermath signal (a police report, a support ticket).
HYPOTHETICAL_RE = re.compile(
    r"\b(if|what if|in case|suppose|should)\b[^.!?\n]{0,50}\b(was|were|is|are|gets?|got)\s+"
    r"(stolen|taken)\b"
)
CONCRETE_RE = re.compile(
    r"(filed a police report|police report|contacted (starlink )?support|"
    r"reached out to support|reported (it|the theft|this) to)"
)


def matched(text: str, phrases: list[str]) -> list[str]:
    return [p for p in phrases if p in text]


def score_post(post: dict, cfg: dict) -> dict:
    signals = cfg["signals"]
    weights = cfg["scoring"]

    title = (post.get("title") or "").lower()
    body = (post.get("selftext") or "").lower()
    blob = f"{title}\n{body}"

    hits = {name: matched(blob, phrases) for name, phrases in signals.items()}

    score = 0.0
    for bucket in ("theft_event", "prevention_ask", "prevention_intent", "recovery_intent"):
        if hits[bucket]:
            score += weights[bucket]
            # A signal in the title is a far stronger indicator than one buried
            # in paragraph six of a rambling post.
            if matched(title, signals[bucket]):
                score += weights["title_bonus"]

    if hits["noise"]:
        score += weights["noise_penalty"]

    # Starlink has to actually be the subject, not an aside. A caretaker-wanted
    # post that mentions a break-in in one paragraph and Starlink in another
    # matches every keyword while being of no use at all, so the theft signal
    # has to sit near a Starlink mention, not merely in the same post.
    subject_at = [m.start() for m in SUBJECT_RE.finditer(blob)]
    if not subject_at:
        score -= 6
    else:
        signal_at = [blob.find(p) for bucket in ("theft_event", "prevention_intent",
                                                 "recovery_intent")
                     for p in hits[bucket]]
        signal_at = [i for i in signal_at if i >= 0]
        if signal_at and not any(abs(si - mi) <= PROXIMITY_CHARS
                                 for si in signal_at for mi in subject_at):
            score -= 7

    if "?" in title or re.search(r"\b(how|what|anyone|any way|advice|recommend|suggestions)\b", title):
        score += weights["question_bonus"]
    if post.get("is_self"):
        score += weights["self_post_bonus"]

    created = post.get("created_utc", 0)
    age_days = max(0.0, (time.time() - created) / 86400)
    decay = 0.5 ** (age_days / weights["recency_half_life_days"])
    engagement = math.log1p(max(0, post.get("num_comments", 0)))

    final = score * (0.55 + 0.45 * decay) + engagement

    # "how do I stop it being stolen" trips the theft_event words without anyone
    # having lost anything, so a victim is identified by first-person phrasing
    # rather than by the presence of the word "stolen".
    victim = bool(VICTIM_RE.search(blob))
    if victim and HYPOTHETICAL_RE.search(blob) and not CONCRETE_RE.search(blob):
        victim = False

    if hits["noise"] and not victim:
        bucket = "peripheral"
    elif victim and (hits["recovery_intent"] or hits["prevention_intent"]):
        bucket = "victim_seeking_answers"
    elif victim:
        bucket = "theft_report"
    elif hits["prevention_ask"] or (hits["prevention_intent"] and hits["theft_event"]):
        # An explicit anti-theft ask, or generic security words in a post that is
        # at least about theft. Generic words alone are how "safe to buy on
        # marketplace?" and "account banned for fraud" got in here.
        bucket = "shopping_for_prevention"
    elif hits["recovery_intent"]:
        bucket = "tracking_discussion"
    elif hits["theft_event"]:
        bucket = "theft_report"
    else:
        bucket = "peripheral"

    return {
        "score": round(final, 2),
        "raw_score": score,
        "bucket": bucket,
        "age_days": round(age_days, 1),
        "hits": {k: v for k, v in hits.items() if v},
    }


# The pitch angle that actually fits each kind of thread. A victim three hours
# after the theft does not want to hear about a subscription.
ANGLES = {
    "shopping_for_prevention": "Best fit. They are actively asking how to secure a dish - answer the question first, mention the app as one option.",
    "victim_seeking_answers": "Strong fit. Already stolen and asking what now - lead with recovery/serial-reporting help, app second.",
    "theft_report": "Medium fit. Venting, not asking. A short 'sorry, here's what stops the next one' reads better than a pitch.",
    "tracking_discussion": "Good fit. GPS/AirTag/serial threads - the app is directly on-topic.",
    "peripheral": "Weak fit. Skim before spending a comment on it.",
}


# --------------------------------------------------------------------------- #
# Crawl
# --------------------------------------------------------------------------- #
def absorb(seen: dict, posts: list[dict], source: str, cfg: dict, cutoff: float) -> None:
    """Score and file each post, merging duplicates found by several queries."""
    for post in posts:
        pid = post.get("id")
        if not pid or post.get("created_utc", 0) < cutoff:
            continue
        if pid in seen:
            seen[pid]["sources"].add(source)
            continue
        verdict = score_post(post, cfg)
        seen[pid] = {
            "_raw": {k: post.get(k) for k in
                     ("title", "selftext", "subreddit", "permalink", "created_utc",
                      "score", "num_comments", "author", "locked", "archived",
                      "over_18", "id")},
            "id": pid,
            "title": post.get("title", ""),
            "subreddit": post.get("subreddit", ""),
            "author": post.get("author", ""),
            "url": f"https://www.reddit.com{post.get('permalink', '')}",
            "created_utc": post.get("created_utc", 0),
            "created": datetime.fromtimestamp(
                post.get("created_utc", 0), tz=timezone.utc
            ).strftime("%Y-%m-%d"),
            "upvotes": post.get("score", 0),
            "num_comments": post.get("num_comments", 0),
            "over_18": post.get("over_18", False),
            "locked": post.get("locked", False),
            "archived": post.get("archived", False),
            "excerpt": " ".join((post.get("selftext") or "").split())[:400],
            "sources": {source},
            **verdict,
        }


def finalize(seen: dict) -> list[dict]:
    for row in seen.values():
        row["sources"] = sorted(row["sources"])
    return sorted(seen.values(), key=lambda r: r["score"], reverse=True)


def crawl_reddit(client: Reddit, cfg: dict, args) -> list[dict]:
    seen: dict[str, dict] = {}
    cutoff = time.time() - args.since_days * 86400
    queries = cfg["queries"]
    subs = [s["name"] for s in cfg["subreddits"]]

    print(f"[crawl] {len(queries)} queries site-wide", file=sys.stderr)
    for q in queries:
        absorb(seen, client.search(q, sort=args.sort, time_filter=args.time_filter,
                                   limit=args.limit), f"site:{q}", cfg, cutoff)

    print(f"[crawl] {len(queries)} queries x {len(subs)} subreddits", file=sys.stderr)
    for sub in subs:
        for q in args.sub_queries or ["starlink stolen", "starlink theft", "starlink security"]:
            absorb(seen, client.search(q, subreddit=sub, sort=args.sort,
                                       time_filter=args.time_filter, limit=args.limit),
                   f"r/{sub}:{q}", cfg, cutoff)

    if args.scan_new:
        print(f"[crawl] scanning /new in {len(subs)} subreddits", file=sys.stderr)
        for sub in subs:
            absorb(seen, client.listing(sub, "new", limit=args.limit), f"r/{sub}:new", cfg, cutoff)

    return finalize(seen)


def crawl_arctic(client: "ArcticShift", cfg: dict, args) -> list[dict]:
    """Arctic Shift has no site-wide full-text search - every query is scoped to
    a subreddit, so the sweep is subreddit x term x field instead."""
    seen: dict[str, dict] = {}
    cutoff = time.time() - args.since_days * 86400
    plan = cfg["arctic_shift"]
    native = set(plan["starlink_native_subs"])

    subs = [s["name"] for s in cfg["subreddits"]]
    total = sum(
        len(plan["title_terms_native" if s in native else "title_terms_general"])
        + len(plan["body_terms_native" if s in native else "body_terms_general"])
        for s in subs
    )
    print(f"[crawl] arctic-shift: {total} queries across {len(subs)} subreddits", file=sys.stderr)

    done = 0
    for sub in subs:
        kind = "native" if sub in native else "general"
        for field, terms in (("title", plan[f"title_terms_{kind}"]),
                             ("selftext", plan[f"body_terms_{kind}"])):
            for term in terms:
                posts = client.search_sub(sub, field, term, after=cutoff, limit=args.limit)
                absorb(seen, posts, f"r/{sub}:{field}={term}", cfg, cutoff)
                done += 1
                if done % 20 == 0:
                    print(f"[crawl] {done}/{total} queries, {len(seen)} unique posts",
                          file=sys.stderr)

    return finalize(seen)


def render_markdown(rows: list[dict], cfg: dict, args) -> str:
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    keep = [r for r in rows if r["score"] >= args.min_score
            and not r["locked"] and not r["archived"]]
    if args.buckets:
        keep = [r for r in keep if r["bucket"] in args.buckets]

    lines = [
        "# Starlink theft threads - promotion targets",
        "",
        f"Generated {stamp} | window: last {args.since_days} days | "
        f"{len(keep)} of {len(rows)} hits above threshold {args.min_score}"
        + (f", filtered to {', '.join(args.buckets)}" if args.buckets else ""),
        "",
        "Read each subreddit's rules before commenting. Most ban unsolicited "
        "promotion; the ones that allow it expect you to disclose that you built "
        "the thing. Answer the question first, link second.",
        "",
    ]

    order = ["shopping_for_prevention", "victim_seeking_answers",
             "tracking_discussion", "theft_report", "peripheral"]
    for bucket in order:
        group = [r for r in keep if r["bucket"] == bucket]
        if not group:
            continue
        lines += [f"## {bucket.replace('_', ' ').title()} ({len(group)})",
                  "", f"_{ANGLES[bucket]}_", ""]
        for r in group:
            hits = ", ".join(sorted({h for v in r["hits"].values() for h in v})[:8])
            lines += [
                f"### [{r['title']}]({r['url']})",
                "",
                f"- **r/{r['subreddit']}** | {r['created']} ({r['age_days']:.0f}d ago) | "
                f"{r['upvotes']} upvotes | {r['num_comments']} comments | fit **{r['score']}**",
                f"- Signals: {hits or 'none'}",
            ]
            if r["excerpt"]:
                lines.append(f"- > {r['excerpt']}")
            lines.append("")

    skipped = [r for r in rows if r["score"] >= args.min_score and (r["locked"] or r["archived"])]
    if skipped:
        lines += [f"## Locked or archived ({len(skipped)})", "",
                  "Relevant but you cannot comment on them. Useful as evidence of demand.", ""]
        lines += [f"- [{r['title']}]({r['url']}) - r/{r['subreddit']}, {r['created']}"
                  for r in skipped]
        lines.append("")

    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", type=pathlib.Path, default=DEFAULT_CONFIG)
    ap.add_argument("--out", type=pathlib.Path, default=ROOT / "reports")
    ap.add_argument("--since-days", type=int, default=365)
    ap.add_argument("--limit", type=int, default=100, help="results per query")
    ap.add_argument("--sort", default="relevance",
                    choices=["relevance", "new", "top", "comments"])
    ap.add_argument("--time-filter", default="all",
                    choices=["hour", "day", "week", "month", "year", "all"])
    ap.add_argument("--min-score", type=float, default=None)
    ap.add_argument("--sub-queries", nargs="*", default=None,
                    help="override the per-subreddit query set")
    ap.add_argument("--scan-new", action="store_true",
                    help="also sweep /new in every configured subreddit")
    ap.add_argument("--state", type=pathlib.Path, default=None,
                    help="JSON file of already-seen post ids; new hits only")
    ap.add_argument("--backend", default="auto", choices=["auto", "reddit", "arcticshift"],
                    help="auto probes Reddit first and falls back to the archive")
    ap.add_argument("--pause", type=float, default=4.0,
                    help="seconds between Arctic Shift requests")
    ap.add_argument("--attempts", type=int, default=10,
                    help="retries per Arctic Shift query; ~2 of 3 time out server-side")
    ap.add_argument("--buckets", nargs="*", default=None,
                    help="only report these buckets, e.g. --buckets shopping_for_prevention")
    ap.add_argument("--from-raw", type=pathlib.Path, nargs="+", default=None,
                    help="re-score a cached raw-YYYY-MM-DD.json instead of crawling")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    cfg = json.loads(args.config.read_text())
    if args.min_score is None:
        args.min_score = cfg["scoring"]["min_score_to_report"]

    if args.from_raw:
        posts = []
        for path in args.from_raw:
            batch = json.loads(path.read_text())
            posts.extend(batch)
            print(f"[rescore] {len(batch)} posts from {path.name}", file=sys.stderr)
        seen: dict[str, dict] = {}
        absorb(seen, posts, "cache", cfg, time.time() - args.since_days * 86400)
        rows = finalize(seen)
        print(f"[rescore] {len(rows)} cached posts re-scored", file=sys.stderr)
        args.out.mkdir(parents=True, exist_ok=True)
        day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        for r in rows:
            r.pop("_raw", None)
        (args.out / f"threads-{day}.json").write_text(json.dumps(rows, indent=2))
        report = args.out / f"threads-{day}.md"
        report.write_text(render_markdown(rows, cfg, args))
        above = sum(1 for r in rows if r["score"] >= args.min_score)
        print(f"[done] {above} above threshold -> {report}", file=sys.stderr)
        return 0

    backend = args.backend
    if backend == "auto":
        probe = Reddit(verbose=args.verbose)
        backend = "reddit" if probe.reachable() else "arcticshift"
        print(f"[backend] auto-selected {backend}", file=sys.stderr)

    if backend == "reddit":
        client = Reddit(verbose=args.verbose)
        rows = crawl_reddit(client, cfg, args)
    else:
        client = ArcticShift(pause=args.pause, attempts=args.attempts, verbose=args.verbose)
        rows = crawl_arctic(client, cfg, args)
        dead = sorted(s for s, n in client.sub_hits.items() if n == 0)
        if dead:
            print(f"[note] every query came back empty for: {', '.join(dead)} "
                  f"(check those subreddit names)", file=sys.stderr)

    if client.ok_requests == 0:
        print(
            f"[fail] every request via {backend} failed - no report written. Check "
            "network access and whether this IP is blocked.",
            file=sys.stderr,
        )
        return 2

    if args.state:
        previous = set(json.loads(args.state.read_text())) if args.state.exists() else set()
        fresh = [r for r in rows if r["id"] not in previous]
        args.state.parent.mkdir(parents=True, exist_ok=True)
        args.state.write_text(json.dumps(sorted(previous | {r["id"] for r in rows}), indent=0))
        print(f"[state] {len(rows) - len(fresh)} already seen, {len(fresh)} new", file=sys.stderr)
        rows = fresh

    args.out.mkdir(parents=True, exist_ok=True)
    day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    (args.out / f"raw-{day}.json").write_text(
        json.dumps([r.pop("_raw") for r in rows], indent=2))
    (args.out / f"threads-{day}.json").write_text(json.dumps(rows, indent=2))
    report = args.out / f"threads-{day}.md"
    report.write_text(render_markdown(rows, cfg, args))

    above = sum(1 for r in rows if r["score"] >= args.min_score)
    print(f"[done] {len(rows)} threads scored, {above} above threshold -> {report}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
