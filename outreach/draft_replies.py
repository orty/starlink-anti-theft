#!/usr/bin/env python3
"""Turn a sweep report into paste-ready reply drafts.

Reads the JSON a crawl run produced, picks the threads worth a comment, and
writes a worksheet: one section per thread with the link, the question being
asked, the subreddit's promotion rules, and a draft to rewrite and paste.

Posting is manual and deliberately so. At a few comments a month, pasting one
yourself keeps a human eye on every reply, and near-identical comments posted
in bulk are what gets an account and its links banned sitewide.

  python outreach/draft_replies.py --report reports/threads-2026-08-23.json \
      --buckets shopping_for_prevention --max-age-days 120
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
import textwrap
import time

APP_NAME = "Starlink Guard"
APP_URL = "https://play.google.com/store/apps/details?id=orty.starlink_guard"

# Subreddits that ban or tightly restrict self-promotion. Not exhaustive, and
# rules change - it flags what to check, it does not clear anything. Being free
# does not exempt a comment from these: the rules are about who is promoting.
STRICT_SUBS = {
    "Starlink": "Self-promotion and referral spam rule; be useful, disclose, no bare links.",
    "homesecurity": "Bans vendor promotion outright, free or not. Answer without the link.",
    "preppers": "Self-promotion needs mod approval.",
    "RVLiving": "No advertising; a disclosed mention inside a real answer is the most that fits.",
    "vandwellers": "Strict on commercial posts.",
    "Truckers": "Low tolerance for outside promotion.",
    "HomeNetworking": "No vendor spam; technical answers only.",
}

# What the thread is actually about, so the reply can open on their situation
# rather than a script. Mechanical, but it is scaffolding, not a comment.
CONTEXTS = [
    ("boat", ["boat", "canal", "marina", "sailing", "yacht"]),
    ("RV or motorhome", ["rv", "motorhome", "caravan", "camper", "trailer"]),
    ("van", ["van", "vanlife", "van life"]),
    ("vehicle roof", ["truck", "car roof", "roof of my", "vehicle", "overland"]),
    ("farm or rural property", ["farm", "ranch", "rural", "acreage", "pasture"]),
    ("home or garden", ["patio", "terrace", "garden", "yard", "balcony", "flat", "apartment"]),
    ("travel or abroad", ["international", "abroad", "trip", "travel", "border"]),
]
CONCERNS = [
    ("Do the standard mounts/bolts actually resist theft", ["bolt", "screw", "mount", "tamper"]),
    ("Is a stolen dish usable to the thief (bricking, account lock)",
     ["brick", "locked", "useless", "resale", "re-register", "registered", "serial"]),
    ("Concealment vs. hardening", ["conceal", "camo", "hide", "cover", "visible", "obvious"]),
    ("Having to take it in and out every time",
     ["take it in", "taking it in", "every trip", "remove it every", "faff", "unattended"]),
    ("Recovery after the fact", ["track", "gps", "airtag", "police", "recover", "insurance"]),
]


def contexts_for(blob: str) -> list[str]:
    return [name for name, keys in CONTEXTS if any(k in blob for k in keys)]


def concerns_for(blob: str) -> list[str]:
    return [name for name, keys in CONCERNS if any(k in blob for k in keys)]


def build_draft(row: dict) -> str:
    body = OPENERS.get(row["bucket"], OPENERS["shopping_for_prevention"])
    if row["bucket"] in NO_PITCH:
        return body + "\n\n[No pitch in this bucket - they are venting, not asking.]"
    return body + DISCLOSURE


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--report", type=pathlib.Path, required=True)
    ap.add_argument("--raw", type=pathlib.Path, default=None,
                    help="raw-*.json from the same run, for full post bodies")
    ap.add_argument("--out", type=pathlib.Path, default=pathlib.Path("outreach/drafts.md"))
    ap.add_argument("--buckets", nargs="*", default=["shopping_for_prevention"])
    ap.add_argument("--min-score", type=float, default=8.0)
    ap.add_argument("--max-age-days", type=int, default=120,
                    help="older threads are drafted but flagged: nobody reads a necro comment")
    ap.add_argument("--limit", type=int, default=10,
                    help="cap the batch; a pile of similar comments is what gets accounts banned")
    args = ap.parse_args()

    rows = json.loads(args.report.read_text())
    raw = {p["id"]: p for p in json.loads(args.raw.read_text())} if args.raw else {}
    picked = [r for r in rows
              if r["score"] >= args.min_score
              and r["bucket"] in args.buckets
              and not r["locked"] and not r["archived"]]
    picked.sort(key=lambda r: r["score"], reverse=True)

    sections = []
    now = time.time()
    stale_count = 0
    for row in picked[:args.limit]:
        age = (now - row["created_utc"]) / 86400
        warnings = []
        if age > args.max_age_days:
            stale_count += 1
            warnings.append(f"**Stale** - {age:.0f} days old. The author has moved on; "
                            f"only worth a comment for the search-traffic value.")
        if row["subreddit"] in STRICT_SUBS:
            warnings.append(f"**Rules** - {STRICT_SUBS[row['subreddit']]}")

        block = [
            f"## [{row['title']}]({row['url']})",
            "",
            f"r/{row['subreddit']} | {age:.0f} days old | {row['num_comments']} comments "
            f"| fit {row['score']}",
            "",
        ]
        block += [f"- {w}" for w in warnings]
        if warnings:
            block.append("")
        full = raw.get(row["id"], {}).get("selftext") or row["excerpt"]
        full = " ".join(full.split())
        if full:
            quoted = "\n".join(f"> {line}" for line in textwrap.wrap(full[:1200], 100))
            block += ["**They asked:**", "", quoted, ""]
        blob = f"{row['title']}\n{full}".lower()
        ctx = contexts_for(blob)
        con = concerns_for(blob)
        block += ["**Their setup:** " + (", ".join(ctx) if ctx else "not stated"), ""]
        if con:
            block += ["**What they are actually asking:**", ""]
            block += [f"- {c}" for c in con]
            block.append("")
        block += [
            "**Write the reply here.** Open on their specific situation above, in "
            "your own words. One or two sentences on what you would do, then the "
            "app only where it genuinely answers the question they asked - "
            f"disclosed as yours, and free. Do not reuse wording from another "
            f"reply in this file.",
            "",
            "```",
            "",
            "```",
            "",
        ]
        sections.append("\n".join(block))

    header = [
        f"# Reply worksheet - {APP_NAME}",
        "",
        f"{len(sections)} threads, {stale_count} of them stale. Posting is manual, "
        "and the replies are yours to write - this file deliberately ships no "
        "canned text.",
        "",
        "Two rules decide whether this works. Answer the question first and "
        "disclose that you built it - free or not, most of these subs remove "
        "anything that reads as promotion. And write each reply from scratch: "
        "near-identical comments across threads is precisely what spam detection "
        "looks for, and it costs the account and the link domain, not one comment.",
        "",
        f"App: {APP_URL} - confirm the listing is public before linking it. "
        "See app-facts.md for what the app does and, more importantly, the limits "
        "that belong in the reply.",
        "",
        "---",
        "",
    ]

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(header) + "\n---\n\n".join(sections))
    print(f"[drafts] {len(sections)} threads written to {args.out} "
          f"({stale_count} stale, {len(sections) - stale_count} live)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
