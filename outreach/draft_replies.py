#!/usr/bin/env python3
"""Turn a sweep report into per-thread reply drafts for review.

Reads the JSON a crawl run produced, picks the threads worth a comment, and
writes one draft per thread to a review file. Nothing is posted here - drafts
land with status "needs_review" and stay that way until you edit the text and
flip them to "approved" yourself.

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
APP_URL = "https://play.google.com/store/apps/details?id=YOUR.PACKAGE.ID"

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
    ap.add_argument("--out", type=pathlib.Path, default=pathlib.Path("outreach/drafts.json"))
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

    drafts = []
    now = time.time()
    for row in picked[:args.limit]:
        age = (now - row["created_utc"]) / 86400
        warnings = []
        if age > args.max_age_days:
            warnings.append(f"STALE: {age:.0f} days old - the author is long gone. "
                            f"Only worth it for the search-traffic value.")
        if row["subreddit"] in STRICT_SUBS:
            warnings.append(f"RULES: {STRICT_SUBS[row['subreddit']]}")
        drafts.append({
            "status": "needs_review",
            "url": row["url"],
            "subreddit": row["subreddit"],
            "title": row["title"],
            "bucket": row["bucket"],
            "posted_days_ago": round(age),
            "their_post": row["excerpt"],
            "warnings": warnings,
            "draft": build_draft(row),
        })

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(drafts, indent=2))

    stale = sum(1 for d in drafts if any(w.startswith("STALE") for w in d["warnings"]))
    print(f"[drafts] {len(drafts)} written to {args.out}", file=sys.stderr)
    print(f"[drafts] {stale} are stale; {len(drafts) - stale} are live threads", file=sys.stderr)
    print("\nEvery draft is generic on purpose. Rewrite each one to answer the actual\n"
          "question in 'their_post' - a comment that reads as a template is the one\n"
          "that gets removed. Then set status to \"approved\" on the ones you want sent.",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
