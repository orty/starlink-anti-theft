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
import time

APP_NAME = "Starlink Guard"
APP_PLACEHOLDER = "YOUR.PACKAGE.ID"
APP_URL = f"https://play.google.com/store/apps/details?id={APP_PLACEHOLDER}"

# Subreddits that ban or tightly restrict self-promotion. Not exhaustive, and
# rules change - it flags what to check, it does not clear anything.
STRICT_SUBS = {
    "Starlink": "Rule on self-promotion and referral spam; be useful, disclose, no bare links.",
    "homesecurity": "Bans vendor promotion outright. Answer without the link.",
    "preppers": "Self-promotion needs mod approval.",
    "RVLiving": "No advertising; a disclosed mention inside a real answer is the most that fits.",
    "vandwellers": "Strict on commercial posts.",
    "Truckers": "Low tolerance for outside promotion.",
    "HomeNetworking": "No vendor spam; technical answers only.",
}

OPENERS = {
    "shopping_for_prevention": (
        "The three things that actually cut dish theft, in order of value per dollar:\n"
        "1. Make it boring to look at - a dish at ground level in the open is the "
        "advertisement. Roofline or behind a screen beats any lock.\n"
        "2. Security-bolt the mount so it needs a tool and two minutes, not a yank.\n"
        "3. Record the serial (base of the unit, also in your account) and photograph "
        "the install - without it the police report and the Starlink stolen-unit "
        "report both go nowhere."
    ),
    "victim_seeking_answers": (
        "Sorry - rotten thing to come home to. Worth doing today, in order:\n"
        "- Report it to Starlink support with the serial and your account, so the unit "
        "can't be re-registered to someone else. That's what kills its resale value.\n"
        "- File a police report with that serial in it.\n"
        "- Set a saved search on local marketplace listings for \"Starlink\"."
    ),
    "tracking_discussion": (
        "Worth knowing what each approach buys you: an AirTag tells you where it went "
        "but not who has it, and thieves increasingly scan for them. The serial number "
        "is what makes the unit useless to them, because Starlink won't re-register a "
        "reported one."
    ),
    "theft_report": (
        "That sucks. If you haven't yet - report the serial to Starlink support so it "
        "can't be registered to another account. Won't get yours back, but it makes it "
        "worthless to whoever took it."
    ),
}

DISCLOSURE = (
    f"\n\nFull disclosure, I built {APP_NAME} ({APP_URL}) for the serial-and-photos part "
    f"of this, so weigh that accordingly - the physical steps above matter more than any app."
)

NO_PITCH = {"theft_report"}


def build_draft(row: dict) -> str:
    body = OPENERS.get(row["bucket"], OPENERS["shopping_for_prevention"])
    if row["bucket"] in NO_PITCH:
        return body + "\n\n[No pitch in this bucket - they are venting, not asking.]"
    return body + DISCLOSURE


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--report", type=pathlib.Path, required=True)
    ap.add_argument("--out", type=pathlib.Path, default=pathlib.Path("outreach/drafts.md"))
    ap.add_argument("--buckets", nargs="*", default=["shopping_for_prevention"])
    ap.add_argument("--min-score", type=float, default=8.0)
    ap.add_argument("--max-age-days", type=int, default=120,
                    help="older threads are drafted but flagged: nobody reads a necro comment")
    ap.add_argument("--limit", type=int, default=10,
                    help="cap the batch; a pile of similar comments is what gets accounts banned")
    args = ap.parse_args()

    rows = json.loads(args.report.read_text())
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
        if row["excerpt"]:
            block += ["**They asked:**", "", f"> {row['excerpt']}", ""]
        block += ["**Draft** - rewrite this to answer their actual question before "
                  "pasting it:", "", "```", build_draft(row), "```", ""]
        sections.append("\n".join(block))

    header = [
        f"# Reply worksheet - {APP_NAME}",
        "",
        f"{len(sections)} threads, {stale_count} of them stale. Posting is manual: "
        "open the link, rewrite the draft to fit the thread, paste it yourself.",
        "",
        "Two rules that decide whether this works at all. Answer the question first "
        "and disclose that you built the app - most of these subs remove anything "
        "that reads as an advert. And never paste the same text twice: near-identical "
        "comments across threads is the exact pattern that gets an account and its "
        "links banned.",
        "",
        f"Replace `{APP_PLACEHOLDER}` with the real Play Store link before sending "
        "anything.",
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
