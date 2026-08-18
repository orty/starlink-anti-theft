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

It prompts for a store password, your name/organisation, and a key password. Use a real password
manager entry for these.

**Back the `.jks` file up somewhere safe.** With Play App Signing (on by default for new apps)
Google holds the actual app signing key and you can request an upload-key reset if you lose this
one, so it is recoverable — but a reset takes days, and losing it is still an avoidable headache.

### Build locally with it

Create `keystore.properties` in the repository root — it is git-ignored:

```properties
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=…
keyAlias=starlink-guard
keyPassword=…
```

Then `./gradlew :app:bundleRelease` produces a signed
`app/build/outputs/bundle/release/app-release.aab`.

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
| `ANDROID_KEYSTORE_PASSWORD` | store password |
| `ANDROID_KEY_ALIAS` | `starlink-guard` |
| `ANDROID_KEY_PASSWORD` | key password |

The `bundle` job picks these up automatically. Without them it still builds, but prints a warning
and produces an **unsigned** bundle that Play will reject. The keystore is written to the runner's
temporary directory and never appears in logs.

---

## 2. Create the app in Play Console

**All apps → Create app.** App name `Starlink Guard`, English (US), **App**, **Free**.

> ⚠️ **Read the trademark note in section 6 before settling on the name.**

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

## 8. Ship it

1. Push a commit — CI builds `starlink-guard-release-aab`.
2. Download the artifact and unzip to get `app-release.aab`.
3. Play Console → **Testing → Internal testing → Create new release**.
4. Upload the `.aab`, add release notes, save, review, roll out.
5. On the **Testers** tab, create an email list containing your own Google account, then copy the
   opt-in link and open it on your phone to install through Play.

**Every upload needs a higher `versionCode`** than the last. Bump it in `app/build.gradle.kts`
(`versionCode = 2`, and `versionName` when it is a meaningful change) or Play rejects the bundle.

---

## Notes for later

- **R8 is disabled** (`isMinifyEnabled = false`). Its failures appear at runtime, not build time,
  and the app has not been exercised on hardware. Once you have used it on a real device, flip it
  on — `app/proguard-rules.pro` already carries the kotlinx.serialization keeps — and re-test the
  webhook and event log, which are the parts reflection-based stripping would break first.
- **Target API 36** is required for new apps from 31 August 2026, and Play raises this yearly.
  Expect to bump `targetSdk` roughly every August to keep shipping updates.
