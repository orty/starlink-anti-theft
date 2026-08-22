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
# Scoring
# --------------------------------------------------------------------------- #
VICTIM_RE = re.compile(
    r"(stole (my|our)|someone stole|thieves took|"
    r"\b(my|our)\b[^.!?\n]{0,60}\b(was|were|got|has been|have been)\s+(stolen|taken|ripped off)|"
    r"had\s+(my|our)\b[^.!?\n]{0,40}\bstolen|"
    r"(my|our)\s+(starlink|dish|dishy)[^.!?\n]{0,40}\b(gone|missing|disappeared))"
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
    for bucket in ("theft_event", "prevention_intent", "recovery_intent"):
        if hits[bucket]:
            score += weights[bucket]
            # A signal in the title is a far stronger indicator than one buried
            # in paragraph six of a rambling post.
            if matched(title, signals[bucket]):
                score += weights["title_bonus"]

    if hits["noise"]:
        score += weights["noise_penalty"]

    # Starlink has to actually be the subject, not an aside.
    if "starlink" not in blob and "dishy" not in blob:
        score -= 6

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

    if hits["noise"] and not victim:
        bucket = "peripheral"
    elif victim and (hits["recovery_intent"] or hits["prevention_intent"]):
        bucket = "victim_seeking_answers"
    elif victim:
        bucket = "theft_report"
    elif hits["prevention_intent"]:
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
def crawl(client: Reddit, cfg: dict, args) -> list[dict]:
    seen: dict[str, dict] = {}
    cutoff = time.time() - args.since_days * 86400

    def absorb(posts: list[dict], source: str) -> None:
        for post in posts:
            pid = post.get("id")
            if not pid or post.get("created_utc", 0) < cutoff:
                continue
            if pid in seen:
                seen[pid]["sources"].add(source)
                continue
            verdict = score_post(post, cfg)
            seen[pid] = {
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

    queries = cfg["queries"]
    subs = [s["name"] for s in cfg["subreddits"]]

    print(f"[crawl] {len(queries)} queries site-wide", file=sys.stderr)
    for q in queries:
        absorb(client.search(q, sort=args.sort, time_filter=args.time_filter, limit=args.limit),
               f"site:{q}")

    print(f"[crawl] {len(queries)} queries x {len(subs)} subreddits", file=sys.stderr)
    for sub in subs:
        for q in args.sub_queries or ["starlink stolen", "starlink theft", "starlink security"]:
            absorb(client.search(q, subreddit=sub, sort=args.sort,
                                 time_filter=args.time_filter, limit=args.limit),
                   f"r/{sub}:{q}")

    if args.scan_new:
        print(f"[crawl] scanning /new in {len(subs)} subreddits", file=sys.stderr)
        for sub in subs:
            absorb(client.listing(sub, "new", limit=args.limit), f"r/{sub}:new")

    for row in seen.values():
        row["sources"] = sorted(row["sources"])

    return sorted(seen.values(), key=lambda r: r["score"], reverse=True)


def render_markdown(rows: list[dict], cfg: dict, args) -> str:
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    keep = [r for r in rows if r["score"] >= args.min_score
            and not r["locked"] and not r["archived"]]

    lines = [
        "# Starlink theft threads - promotion targets",
        "",
        f"Generated {stamp} | window: last {args.since_days} days | "
        f"{len(keep)} of {len(rows)} hits above threshold {args.min_score}",
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
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    cfg = json.loads(args.config.read_text())
    if args.min_score is None:
        args.min_score = cfg["scoring"]["min_score_to_report"]

    client = Reddit(verbose=args.verbose)
    rows = crawl(client, cfg, args)

    if client.ok_requests == 0:
        print(
            "[fail] every request to Reddit failed - no report written. Check network "
            "access, proxy settings, and whether this IP is blocked by Reddit.",
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
    (args.out / f"threads-{day}.json").write_text(json.dumps(rows, indent=2))
    report = args.out / f"threads-{day}.md"
    report.write_text(render_markdown(rows, cfg, args))

    above = sum(1 for r in rows if r["score"] >= args.min_score)
    print(f"[done] {len(rows)} threads scored, {above} above threshold -> {report}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
