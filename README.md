# starlink-anti-theft — Reddit lead finder

Finds Reddit threads about Starlink theft that are worth replying to, ranks them
by how well they fit an anti-theft app, and writes a Markdown report grouped by
what kind of reply each thread needs.

It exists because the useful threads are not the ones with the most upvotes.
A viral "my dish got stolen" post is mostly people saying "that sucks"; the
thread where someone asks *how do I stop this happening* is the one that
converts. The scorer is tuned to surface the second kind.

## Setup

```bash
pip install -r requirements.txt
```

Credentials are optional but strongly recommended — anonymous requests are
throttled hard and Reddit blocks most datacenter IPs outright.

Create a **script**-type app at https://www.reddit.com/prefs/apps, then:

```bash
export REDDIT_CLIENT_ID=...
export REDDIT_CLIENT_SECRET=...
export REDDIT_USERNAME=...          # optional, raises the rate limit
export REDDIT_PASSWORD=...
export REDDIT_USER_AGENT="starlink-anti-theft-lead-finder/1.0 (by /u/yourname)"
```

## Usage

```bash
# Full sweep of the last year
python reddit_crawl.py --since-days 365

# Just what's new this week, sorted by recency
python reddit_crawl.py --since-days 7 --sort new --time-filter week

# Also read /new in every configured subreddit, not only search
python reddit_crawl.py --scan-new

# Weekly cron: report only threads never seen before
python reddit_crawl.py --since-days 14 --state .state/seen.json
```

Output lands in `reports/threads-YYYY-MM-DD.{md,json}` — the Markdown is the
one to read, the JSON is there for anything downstream.

## What it searches

`config/targets.json` holds the whole search surface, and is meant to be edited:

- **queries** — ~23 phrasings run site-wide, from `starlink stolen` through
  `airtag starlink dish`.
- **subreddits** — 26 subs in four clusters: Starlink itself; mobile users
  (RV, van, overlanding, trucking, boating); fixed rural installs (offgrid,
  homestead, ranching, farming, construction); and the country subs where
  Starlink theft is common enough to be its own genre (South Africa, Kenya,
  Nigeria, Zimbabwe, Brazil, Mexico, Philippines). Each carries a note on why
  it's on the list.
- **signals** — the keyword sets behind scoring, split into theft events,
  prevention intent, recovery intent, and noise.

## How threads are scored

Score combines signal matches (prevention intent weighs heaviest, at 5, because
it converts best), a bonus when the signal is in the title rather than buried in
the body, a bonus for questions and self-posts, comment-count engagement, and a
120-day recency half-life. Noise terms (`stolen valor`, `stole the show`) and
posts where Starlink isn't actually the subject are pushed below the threshold.

Each thread is then bucketed by what kind of reply it needs:

| Bucket | What it is | Reply posture |
| --- | --- | --- |
| `shopping_for_prevention` | Asking how to secure a dish, nothing stolen yet | Best fit — answer, then mention the app |
| `victim_seeking_answers` | Already stolen, asking what now | Lead with serial reporting and recovery |
| `tracking_discussion` | GPS / AirTag / serial threads | On-topic, the app belongs in the answer |
| `theft_report` | Venting, not asking | Sympathy and one useful fact; no pitch |
| `peripheral` | Weak match | Skim before spending a comment |

Victim classification keys off first-person phrasing (`someone stole my…`,
`my dish was taken`) rather than the word "stolen" alone — otherwise every
"how do I stop it being stolen" question gets misfiled as a theft report.

Locked and archived threads are separated out at the end of the report: you
can't comment on them, but they're evidence of demand.

## Replying

`outreach/templates.md` has a draft reply per bucket. They all follow the same
shape — answer the question first, disclose that you built the app, put the link
second — because that is the only version of this that survives contact with
subreddit mods. Check each sub's self-promotion rules before commenting; several
on the list ban it outright, and being useful without a link still works there.
