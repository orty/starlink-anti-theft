# Publishing to Google Play

Everything needed to get this app onto Play's **internal testing** track, which is the fastest
route to installing it on your own phone through Play. Internal testing is not subject to the
12-testers-for-14-days rule that gates production access on personal developer accounts.

---

## 1. Create the upload key

You do this on your own machine. **The private key must never be committed to this repository or
pasted into a chat** — anyone holding it can sign builds that impersonate your app.

```bash
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -storetype PKCS12 \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias starlink-guard
```

It prompts for a password (twice, to confirm) and then your name and organisation details. Put
that password in a password manager now.

**You are asked for one password, not two.** PKCS12 keystores do not support a separate key
password — that was a feature of the older JKS format. keytool is explicit about it if you try:

```
Warning: Different store and key passwords not supported for PKCS12 KeyStores.
         Ignoring user-specified -keypass value.
```

So wherever the config below asks for a *key* password, use the same value as the store password.

**Back the `.jks` file up somewhere safe.** With Play App Signing (on by default for new apps)
Google holds the actual app signing key and you can request an upload-key reset if you lose this
one, so it is recoverable — but a reset takes days, and losing it is still an avoidable headache.

### Build locally with it

Create `keystore.properties` in the repository root — it is git-ignored:

```properties
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=your-password
keyAlias=starlink-guard
keyPassword=your-password
```

`storePassword` and `keyPassword` are the same value for a PKCS12 keystore. Android's signing
config still expects both fields, so both are set.

Then `./gradlew :app:bundleRelease` produces a signed
`app/build/outputs/bundle/release/orty.starlink_guard-release.aab`.

Build outputs are named from `archivesName` in `app/build.gradle.kts`. CI trims the
variant suffix off the two shipping artifacts, so what you download is exactly
`orty.starlink_guard.aab` and `orty.starlink_guard.apk`; local builds keep the suffix so a
debug and a release APK cannot quietly overwrite one another.

### Build in CI with it

Encode the keystore, then add four repository secrets under
**Settings → Secrets and variables → Actions**:

```bash
base64 -w0 upload-keystore.jks    # Linux
base64 -i upload-keystore.jks | tr -d '\n'   # macOS
```

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the base64 blob printed above |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `starlink-guard` |
| `ANDROID_KEY_PASSWORD` | **the same password again** — see the PKCS12 note above |

The `bundle` job picks these up automatically. Without them it still builds, but prints a warning
and produces an **unsigned** bundle that Play will reject. The keystore is written to the runner's
temporary directory and never appears in logs.

---

## 2. Create the app in Play Console

Go to [play.google.com/console](https://play.google.com/console) → **All apps** → **Create app**.

| Field | Value | Notes |
| --- | --- | --- |
| App name | `Starlink Guard` | 30 characters max. Shown on the store and under the icon |
| Default language | English (United States) | |
| App or game | **App** | |
| Free or paid | **Free** | ⚠️ **Irreversible.** A free app can never be switched to paid later. Paid → free is allowed. Pick Free |

Tick the two declarations (Developer Program Policies, US export laws) and press **Create app**.

> ⚠️ **Read the trademark note in section 6 before settling on the name.** The name is editable
> later; the `applicationId` is not.

---

## 3. Complete the App content declarations

Play requires these before *any* release, internal testing included.

| Section | Answer |
| --- | --- |
| **Privacy policy** | URL of the hosted policy — see section 5 |
| **App access** | *All functionality is restricted.* Add the instruction below |
| **Ads** | No ads |
| **Content rating** | Complete the questionnaire; it is a utility with no sensitive content → Everyone |
| **Target audience** | 18+ (or 13+); not designed for children |
| **News app** | No |
| **Data safety** | See below |
| **Government apps** | No |
| **Financial features** | None |
| **Health** | No |

### App access instructions

Reviewers cannot exercise this app without hardware, so tell them:

> This app monitors a Starlink satellite dish over the local network and requires the reviewer's
> device to be connected to a Starlink router's Wi-Fi. Without a dish present the app opens
> normally and shows a "No contact with dish" state; all screens, settings and the Test alarm
> button in Settings remain reachable and can be exercised without hardware. No login or account
> is required.

### Data safety

Answer **"No"** to *Does your app collect or share any of the required user data types?*

The reasoning, in case you are asked to justify it: dish orientation, identifier and coordinates
are read and stored **only on the device**, and Play's definition of collection covers data
transmitted off the device to the developer or a third party the developer engages. The optional
webhook transmits to an endpoint **the user types in themselves**, which falls under Play's
carve-out for data transferred at the user's direction to a destination they choose. The developer
operates no server and receives nothing.

You are the accountable party for these answers, so re-read Play's current Data safety guidance
before submitting and adjust if their wording has moved. If you would rather be conservative,
declaring the webhook payload as "Device or other IDs — collected, not shared, optional, for app
functionality" is the safe over-disclosure and costs nothing.

---

## 4. Declarations for sensitive features

Two things in this app need a written justification in Play Console.

**Foreground service — `connectedDevice`.** Play asks what the service does and usually wants a
short video or description:

> The app maintains an ongoing connection to the user's Starlink satellite terminal over the local
> network, polling its orientation and position every few seconds to detect the dish being moved or
> stolen. Monitoring must continue while the app is in the background, because the alarm is
> worthless if it only works with the app open. A persistent notification shows the armed state and
> latest reading at all times.

**Full-screen intent — `USE_FULL_SCREEN_INTENT`.** Since Android 14 this is granted only to apps
whose core function is calling or alarms/clocks. This app qualifies on the alarm axis:

> The app's core function is a theft alarm. When the dish is moved, the app must present a
> full-screen alarm over the lock screen so the user can see what happened and silence it, in the
> same manner as a clock alarm. The full-screen intent is used exclusively for this alarm and for
> no promotional or engagement purpose.

If the full-screen permission is refused, the app degrades gracefully: it still sounds the alarm
and posts a high-priority notification, and the dashboard shows a warning explaining the
limitation.

---

## 5. Host the privacy policy

`docs/privacy-policy.html` is ready to publish. It needs a public URL.

**This repository is private**, and GitHub Pages on a private repository requires a paid plan, so
the simplest options are:

1. **Make this repository public** and enable Pages (Settings → Pages → Deploy from branch → `docs/`
   folder). The URL becomes `https://orty.github.io/starlink-anti-theft/privacy-policy.html`.
2. **Create a small public repository** just for the policy and enable Pages on it.
3. **Any static host** — Netlify, Cloudflare Pages, your own domain.

Check the contact email in the policy is one you actually read; it is currently
`serge.aradj@gmail.com`.

---

## 6. Store listing copy

**App name** (30 max)

```
Starlink Guard
```

**Short description** (80 max)

```
Sounds an alarm if your dish is moved - even when your phone is on silent.
```

**Full description** (4000 max)

```
Starlink Guard turns your phone into a theft alarm for your satellite dish.

Your dish already knows which way it is pointing. Starlink Guard connects to it over your own
Wi-Fi, reads that orientation several times a minute, and raises a loud alarm the moment the dish
is turned, tilted or carried away.

HOW IT WORKS

Press Arm and the app records where the dish is resting: its azimuth, its elevation, the tilt of
the mast, and its GPS position where available. From then on it watches for two things - a sudden
change, and a slow drift away from that resting position. Either one past your chosen threshold
sets off the alarm.

AN ALARM YOU CAN ACTUALLY HEAR

The alarm plays on the alarm channel at full volume, so a phone left on silent or vibrate still
sounds. With Do Not Disturb access granted it cuts through that too, and the alarm screen appears
over the lock screen.

BUILT TO AVOID FALSE ALARMS

Motorised dishes re-aim themselves, which naively looks exactly like theft. Starlink Guard only
judges orientation while the dish's motors are idle and its attitude estimate has settled, and a
reading must break the threshold twice in a row before anything sounds. There is a grace period
after arming so a dish that is still settling does not wake you up.

EVERYTHING STAYS ON YOUR PHONE

No account, no sign-up, no analytics, no ads, no servers. The app talks to your dish and to
nothing else. If you want an off-device alert you can add your own webhook URL, and that is the
only thing ever sent anywhere.

FEATURES

- Live view of azimuth, elevation, mast tilt and GPS
- Separate thresholds per axis, plus a distance threshold in metres
- Loud alarm that survives silent mode, with a test button
- Full-screen alert over the lock screen
- History of every alarm and dish event
- Optional JSON webhook to your own endpoint
- Restarts monitoring automatically after a reboot

REQUIREMENTS

Your phone must be connected to your Starlink Wi-Fi network to reach the dish. GPS-based
detection additionally requires location access to be enabled in the Starlink app under
Settings > Advanced > Debug Data; on many service plans coordinates are not exposed at all, and
the app falls back to orientation-only monitoring and tells you so.

Starlink is a trademark of Space Exploration Technologies Corp. This app is an independent
project and is not affiliated with, endorsed by, or sponsored by SpaceX.
```

### ⚠️ On the name

`Starlink` is a SpaceX trademark. Play's impersonation and intellectual-property policy prohibits
using another company's trademark in a way that suggests affiliation, and app names are the most
common trigger for a takedown. The disclaimer above helps but does not make you immune, and SpaceX
can file a complaint independently of Google.

For internal testing this is essentially no risk — the listing is not public. Before you ever go
public, consider a name that describes the function instead of the brand, for example **Dish
Guard**, **Dish Sentry** or **Mast Watch**, with "works with Starlink dishes" in the description
where nominative use is on much safer ground. Changing the name later is a store-listing edit, not
a code change; the `applicationId` (`dev.starlinkguard`) is permanent once published, but is never
shown to users.

---

## 7. Graphics

| Asset | Requirement | Status |
| --- | --- | --- |
| App icon | 512×512 PNG, 32-bit | ✅ `store/play-icon-512.png` |
| Feature graphic | 1024×500 PNG | ✅ `store/feature-graphic-1024x500.png` |
| Phone screenshots | 2–8, min 320px, 16:9 or 9:16 | ❌ **you must capture these** |

Screenshots have to come off a real device. Take them once the app is installed and connected to
your dish — the dashboard showing live readings, the armed state, the settings screen with
thresholds, and the alarm screen (use Test alarm) make a solid set of four.

---

## 8. Ship it — the full walkthrough

This is the part that actually puts the app on your phone. Roughly 30 minutes end to end, most of
it filling in forms.

### 8.1 Get a signed bundle out of CI

Once the four secrets from section 1 exist, push any commit. In the **Actions** tab open the
newest **Build** run and wait for the **Release bundle** job to finish, then check its
*Report bundle signing state* step — it must say `Signed with the upload key.` If it prints the
`No signing secrets configured` warning instead, one of the secret names is wrong; they are
case-sensitive and must match exactly.

Download the **`starlink-guard-release-aab`** artifact. GitHub always wraps artifacts in a zip, so
unzip it to get `orty.starlink_guard.aab`. That `.aab` is what you upload — **not** an APK, and not the zip.

### 8.2 Set up the internal testing track and its testers

Play Console → left sidebar → **Testing → Internal testing**. Do the *Testers* tab before the
release, so the opt-in link exists the moment the release goes live.

1. Open the **Testers** tab.
2. **Create email list** → name it something like `Me`.
3. Add the Google account email that your phone is signed in with. **This must be the exact
   account on the device**, not an alias, or the store listing will 404 for you.
4. **Save changes**, then tick the list so it is selected for this track.

Internal testing allows up to 100 testers and has no review delay of the kind closed and open
testing have. It is also exempt from the 12-testers-for-14-days rule that gates production on
personal accounts.

### 8.3 Create the release

**Testing → Internal testing → Releases** tab → **Create new release**.

**App signing.** On your very first release Play offers to enrol you in Play App Signing. Accept
the default, *Let Google manage your app signing key*. From then on the key you generated in
section 1 is your **upload key** — you sign with it, Google verifies it, strips it, and re-signs
with the app signing key it holds. This is why losing your upload key is recoverable.

**Upload.** Drag `orty.starlink_guard.aab` into the *App bundles* box. Play processes it for a few seconds,
then shows the version, size, and the API levels and devices it supports. Sanity-check that it
lists **target SDK 36** and `dev.starlinkguard`.

**Release name.** Auto-filled as `1 (1.0)`. It is internal-only; leave it.

**Release notes.** Required. The `<en-US>` tags must stay:

```
<en-US>
First internal build. Monitors dish orientation and GPS, sounds an alarm on movement.
</en-US>
```

**Save** → **Review release** → **Start rollout to Internal testing** → confirm.

### 8.4 Clear whatever Play blocks you on

Play will not let you roll out until the **Dashboard → Set up your app** checklist is complete —
that is every item in section 3, plus a store listing with the icon, feature graphic and at least
two screenshots. If *Start rollout* is greyed out, the release page lists the exact missing items
at the top; work through them and come back. Errors block rollout, warnings do not.

### 8.5 Install it on your phone

Back on the **Testers** tab there is now a **Copy link** under *How testers join your test*. Open
that link on the phone, signed in as the tester account:

1. The page says *You're invited to test Starlink Guard* → **Accept the invite**.
2. **Download it on Google Play** → the normal store page opens → **Install**.

First propagation usually takes a few minutes and occasionally a couple of hours. If the store
page says the item is not found, you are on the wrong Google account or the rollout has not
finished — those are the only two causes worth checking first.

### 8.6 Every build after this one

**The `versionCode` must increase on every single upload.** Play rejects a bundle that reuses
one, and this is the most common upload failure by a wide margin.

If you use the automated workflow in section 9 this is handled for you — it passes a
`versionCode` derived from the run number. For a manual upload, either edit
`app/build.gradle.kts`:

```kotlin
versionCode = 2          // +1 every upload, always
versionName = "1.0.1"    // human-facing; change when it means something
```

or override it at build time without touching the file:

```bash
./gradlew :app:bundleRelease -PappVersionCode=2 -PappVersionName=1.0.1
```

Then repeat 8.3. Updates reach installed testers through the normal Play update mechanism.

### Failures you are most likely to hit

| Message | Cause |
| --- | --- |
| *Upload a signed APK/bundle* / signed with debug certificate | CI secrets missing, so the bundle was unsigned. Re-check 8.1 |
| *Version code 1 has already been used* | Bump `versionCode` |
| *Your app targets API level X* | `targetSdk` below Play's floor — currently 36 for new apps |
| *You need to complete the content rating questionnaire* | Section 3 is unfinished |
| Tester link shows "item not found" | Wrong Google account on the phone, or rollout still propagating |

---

## 9. Automating uploads (optional)

`.github/workflows/publish-play.yml` builds a signed bundle and pushes it straight to a Play
track, replacing steps 8.1 and 8.3. It runs **only when you trigger it manually** — Actions tab →
*Publish to Play* → **Run workflow** → pick a track and status. Publishing on every push would
create a Play release per commit.

> **The first release must still be made by hand.** The Google Play Developer API refuses to
> touch an app that has never had a release, so section 8 has to be completed manually at least
> once before this workflow will work at all. After that it takes over.

### Setting up the service account

This is a one-off, and it is the fiddly part. It takes about 15 minutes.

1. **Link a Google Cloud project.** Play Console → **Setup → API access**. If no project is
   linked, create or link one from that page.
2. **Enable the API.** In [Google Cloud Console](https://console.cloud.google.com), with that
   project selected: *APIs & Services → Library →* search **Google Play Android Developer API**
   → **Enable**.
3. **Create the service account.** *IAM & Admin → Service accounts → Create service account*.
   Give it a name; it needs **no** Cloud IAM roles — its permissions come from Play, not GCP.
4. **Make a key.** Open the account → *Keys → Add key → Create new key → JSON*. A `.json` file
   downloads. Treat it like a password: it can publish to your Play account.
5. **Grant it access in Play.** Play Console → **Users and permissions → Invite new user**, paste
   the service account's email (`…@….iam.gserviceaccount.com`). Under *App permissions* add this
   app, and grant **Release to testing tracks** — plus *Release to production* only if you intend
   to publish that way. Grant the narrowest set that does the job.
6. **Store it.** Repository → *Settings → Secrets and variables → Actions → New secret*, named
   `PLAY_SERVICE_ACCOUNT_JSON`, with the **entire contents** of the JSON file pasted in.

Permission changes on Play's side can take a few minutes to take effect. A first run that fails
with a permission error is often just impatience.

### Version codes are handled for you

The workflow computes `versionCode` as `100 + <this workflow's run number>` and passes it to the
build, so every automated publish gets a fresh, strictly increasing number without editing
`app/build.gradle.kts`. The offset keeps automated releases clear of the low numbers used by the
first release you upload by hand.

If you need a specific number — say you uploaded something manually at a higher code and need to
get back above it — put it in the **versionCode** field when starting the workflow and it is used
verbatim.

### Release notes

The workflow reads `distribution/whatsnew/whatsnew-en-US`. Edit that file to change what testers
see; add `whatsnew-<BCP47>` siblings for other languages. Play caps release notes at 500
characters.

### On picking the action

The workflow uses [`r0adkll/upload-google-play`](https://github.com/r0adkll/upload-google-play)
(~1k stars, actively maintained, v1.1.5 in April 2026). There are several similar actions on the
Marketplace with single-digit star counts; some of them do not mention the manual-first-release
requirement above, which makes for a confusing first failure.

It is **pinned by commit SHA**, not by tag, because this step receives a credential that can
publish to your Play account and tags can be repointed. To move to a newer version, look up the
SHA of the tag you want:

```bash
git ls-remote https://github.com/r0adkll/upload-google-play refs/tags/v1.1.6
```

and update the `uses:` line, keeping the version in the trailing comment.

Worth being clear-eyed about the trade-off: automating this hands a third-party action a
publishing credential on every run. For a personal app going to internal testing, the manual
upload in section 8 is perhaps two minutes of work and involves trusting nothing new. Automation
earns its keep when you are shipping often, not when you are shipping twice.

---

## Notes for later

- **R8 is disabled** (`isMinifyEnabled = false`). Its failures appear at runtime, not build time,
  and the app has not been exercised on hardware. Once you have used it on a real device, flip it
  on — `app/proguard-rules.pro` already carries the kotlinx.serialization keeps — and re-test the
  webhook and event log, which are the parts reflection-based stripping would break first.
- **Target API 36** is required for new apps from 31 August 2026, and Play raises this yearly.
  Expect to bump `targetSdk` roughly every August to keep shipping updates.
