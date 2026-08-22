# Starlink Guard

An Android anti-theft alarm for a Starlink dish.

The dish knows when it has been disturbed. Over the local network it continuously reports its
own boresight azimuth, elevation and mast tilt angle, and — when the plan and settings allow —
its GPS position. This app polls those values, records where the dish was when you armed it,
and sounds a siren if they change.

The alarm plays on the **alarm audio stream**, which silent and vibrate mode do not mute, so a
phone left face-down on a table still screams.

---

## How it talks to the dish

The dish serves native gRPC at `192.168.100.1:9200` over cleartext HTTP/2 — no TLS, no
authentication for status queries. The app makes two unary calls against
`SpaceX.API.Device.Device/Handle`:

| call | request field | reply field | what it gives |
| --- | --- | --- | --- |
| `get_status` | 1004 | `dish_get_status` = **2004** | azimuth, elevation, mast tilt, attitude filter state, motor state, GPS lock, alerts, serial |
| `get_location` | 1017 | `get_location` = 1017 | latitude, longitude, altitude, accuracy |

Rather than generating protobuf stubs, the app hand-decodes the handful of fields it needs
(`core/src/main/kotlin/dev/starlinkguard/core/proto/Wire.kt`). Unknown fields are skipped by
wire type, so a firmware update that adds or removes fields cannot break parsing — which is
more than can be said for regenerated stubs. Field numbers are documented in
`StarlinkCodec.kt` and were taken from the `.proto` definitions recovered from the dish's own
gRPC server reflection.

### GPS is optional, and often unavailable

`get_location` returns `PERMISSION_DENIED` unless you enable it in the official Starlink app:

> **Settings → Advanced → Debug Data → Starlink Location → allow access on local network**

On many service plans it is not exposed at all. The app treats a missing fix as normal: it
falls back to orientation-only monitoring and tells you so on the dashboard. **Orientation is
the primary signal; GPS is a bonus.**

---

## How it decides something is wrong

All of this lives in `TheftDetector`, which is pure Kotlin with no Android dependencies, and is
covered by unit tests.

When you arm, the app waits out a short settling window and then records a **reference
position**. Every poll after that is compared against it two ways: how far the dish has moved
from that reference (*drift*), and how far it has moved in the last half minute (*sudden*).

Two things make this quiet enough to actually leave switched on:

- **Motorised dishes re-aim themselves.** This is the single biggest source of false alarms.
  Orientation is only judged while the dish reports its actuators are idle. A thief carrying a
  dish away leaves the motors idle while the angles change, so almost no detection power is
  lost. GPS and the dish's own movement alerts are still judged while the motors run, because
  a dish being driven away is still leaving.
- **A breach must repeat.** One bad reading never rings; by default two consecutive polls have
  to agree. Orientation is also ignored until the dish says its attitude filter has converged,
  because before that the reported angles are guesses.

Azimuth comparisons wrap correctly, so a dish pointing just west of north does not look like it
swung 358° every time it crosses the line.

### Defaults

| setting | default |
| --- | --- |
| Poll interval | 10 s |
| Sudden-change window | 30 s |
| Azimuth / elevation / mast tilt | 10° / 5° / 5° |
| GPS radius | 50 m |
| Confirming polls | 2 |
| Settling time after arming | 30 s |
| Ignore the dish's own re-aiming | on |
| Alarm auto-stop | 5 min |

All adjustable in the Settings tab.

---

### Losing contact with the dish

Off by default, enabled under Settings → Triggers. Unplugging the cable or driving off with the
dish stops it answering immediately, which is often the earliest warning available. The dish has
to stay silent for a configurable window — **15 seconds by default**, adjustable from 5 seconds
to 5 minutes.

That default is deliberately aggressive. Someone unplugging a dish is gone in well under a
minute, so waiting several of them to be sure defeats the point; this is one of the few places
where a false alarm is far cheaper than a miss. The cost is that a dish reboot or firmware
update — a minute or two of silence — will also sound the alarm. Raise the window if that trade
is the wrong way round for you.

Silence is measured from the **last successful poll**, not from the first failed one, so the
number means what it says. It can never react faster than the poll interval: at the default 10
second polling, a 15 second window fires about 20 seconds after the dish goes quiet.

**What the Wi-Fi disappearing means depends on your hardware**, and the app cannot work that
out for itself:

- **Gen2 / Gen3** — the router is a separate indoor unit. Steal the dish and the router keeps
  serving Wi-Fi, so the phone stays connected while `192.168.100.1` goes silent. That is the
  signature the defaults look for.
- **Starlink Mini** — the router is inside the dish. Unplug it from AC and the Wi-Fi goes with
  it, so the network vanishing *is* the theft signal rather than a distraction from it.

By default an outage is only counted on the network where the dish was last answering,
identified by `Network.getNetworkHandle()` — deliberately not by SSID, which would drag in
`ACCESS_FINE_LOCATION` for no benefit. Two switches relax that for Mini owners:

| Setting | Counts an outage when | Cost |
| --- | --- | --- |
| *…even if the Wi-Fi disappears* | the phone has no Wi-Fi at all | walking out of range sets off the alarm |
| *…even on a different Wi-Fi* | the phone has joined another network | connecting anywhere else sets off the alarm |

Both are off by default, which is the Gen2/Gen3 setup. Turn both on for a Mini and accept that
leaving with your phone looks the same as the dish being unplugged.

Independently of all of this, an outage is never judged before the dish has answered at least
once since arming, and time spent where the dish could not have been reached resets the clock
rather than pausing it — an outage nobody could observe is not evidence.

## Home-screen widget

A single square button, one cell wide. Its colour and icon are the status, and one tap does the
obvious thing:

| Looks like | Meaning | A tap does |
| --- | --- | --- |
| Grey, open padlock, *OFF* | not watching | arms |
| Amber, *SETTLING* | armed, recording the reference position | disarms |
| Green, closed padlock, *ARMED* | watching, dish where you left it | disarms |
| Amber, *CHECKING* / *NO LINK* | possible movement / dish not answering | disarms |
| Red, exclamation, *SILENCE* | alarm sounding | silences it |

Silencing deliberately outranks disarming: reaching for the widget mid-siren is overwhelmingly a
request for quiet, and quietly disarming as a side effect would leave the dish unwatched without
the user realising.

Tapping a widget is one of the documented exemptions from the Android 12+ ban on starting a
foreground service from the background, so arming from the home screen works with the app
closed. A widget redrawn in a cold process falls back to the armed flag the service already
persists, rather than keeping a second copy of the state that could drift out of step.

## Alarm behaviour

- Plays an alarm ringtone on a loop with `USAGE_ALARM`, at maximum alarm volume, and takes
  audio focus so music cannot duck it. The previous volume is restored afterwards.
- **Ships with its own siren**, used by default: a two-tone warble alternating 800 Hz and
  570 Hz five times a second. The fundamentals sit low enough not to be shrill, while the
  odd harmonics above them carry the sound across a room. A device's stock alarm tone is designed to wake
  someone gently, which is the opposite of what this app wants. It is synthesised rather than
  sampled — see `tools/make_alarm_sound.py` — so there is no licence to honour, and the two
  tones are whole numbers of hertz sounded for exactly one second each, which makes the
  two-second loop close on an exact whole number of cycles and repeat without a click.
- **The sound is configurable** — keep the built-in siren, pick any system alarm tone, or use
  an audio file of your own, under Settings → Alarm. Whatever you choose still plays on the
  alarm channel, so it keeps the silent-mode behaviour below. A chosen sound can never silence
  the alarm: it is the first candidate tried, and the built-in siren sits directly behind it,
  since unlike a media-store file it cannot be deleted, unmounted, or have its permission
  revoked. Settings says when a choice has gone missing rather than quietly substituting one.
- **Silent and vibrate mode do not affect it.** Do Not Disturb is the real obstacle: grant the
  app notification-policy access from the dashboard and it will lift DND while the alarm runs
  and put it back afterwards.
- A full-screen alert appears over the lock screen with a **STOP ALARM** button. There is no
  PIN — stopping is a single tap.
- Stopping re-arms from wherever the dish is now, so the alarm does not immediately fire again
  if the dish really was repositioned.
- **Test alarm** in Settings exercises the whole audio path without touching the dish. Put the
  phone on silent first and confirm you can still hear it.

## Staying alive

Monitoring runs in a `connectedDevice` foreground service. That type was chosen deliberately
over `dataSync`, which on Android 15 is capped at six hours a day and cannot be started from
`BOOT_COMPLETED` — both fatal for an always-on alarm. The app restarts monitoring after a
reboot or an app update if it was armed.

Polling sockets are bound to the Wi-Fi network. Without that, Android decides the Starlink LAN
has no internet and routes the request out over cellular, where it goes nowhere. The webhook
does the opposite and goes out over whatever network can actually reach the internet — if the
dish has been carried off, its Wi-Fi went with it.

## Webhook

Enable it in Settings and give it a URL. On an alarm the app POSTs:

```json
{
  "source": "starlink-anti-theft",
  "schemaVersion": 1,
  "event": "ALARM",
  "message": "Azimuth changed suddenly by 47.2° (limit 10.0°)",
  "timestampMs": 1755400000000,
  "timestampIso": "2026-08-17T04:26:40Z",
  "dishSerial": "ut01000000-00000000-00000000",
  "triggers": [
    {
      "axis": "AZIMUTH",
      "kind": "SUDDEN",
      "description": "Azimuth changed suddenly by 47.2° (limit 10.0°)",
      "referenceValue": 12.5,
      "currentValue": 59.7,
      "delta": 47.2,
      "threshold": 10.0,
      "unit": "°"
    }
  ]
}
```

Works as-is with Home Assistant, n8n, Zapier, or a few lines of your own server code.

---

## Building

### Get an APK without installing anything

Every push builds a debug APK in GitHub Actions. Open the **Build** workflow run and download
the `starlink-guard-debug-apk` artifact.

### Locally

Requires JDK 17 and the Android SDK (Android Studio Ladybug or newer):

```bash
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/orty.starlink_guard-debug.apk
```

### Running the tests without the Android SDK

The protocol and detection logic live in `:core`, a plain Kotlin/JVM module. It needs no
Android SDK and no access to Google's Maven, so it builds and tests anywhere with a JDK:

```bash
./gradlew -PskipAndroidApp=true :core:test
```

That flag drops the Android module from the build entirely. Repository content filters in
`settings.gradle.kts` keep Google's Maven out of `:core`'s resolution path so it resolves
purely from Maven Central.

### Release builds for Google Play

`./gradlew :app:bundleRelease` produces the Android App Bundle that Play accepts. Signing comes
from a git-ignored `keystore.properties` locally, or from repository secrets in CI; without
either, the build still succeeds and emits an unsigned bundle.

See **[docs/PLAY_RELEASE.md](docs/PLAY_RELEASE.md)** for the upload key, the Play Console
declarations this app's permissions require, store listing copy, and the graphics in `store/`.

---

## Using it

1. Install the APK and connect the phone to the Starlink Wi-Fi.
2. Open the app. The dashboard should show live azimuth, elevation and tilt within a few
   seconds. If it says *No contact*, you are not on the dish's network.
3. Work through the **Finish setting up** card — Do Not Disturb access, full-screen alerts, and
   battery-optimisation exemption.
4. Hit **Test alarm** with the phone on silent and confirm it is loud.
5. Press **Arm**. After the settling window the dashboard shows the recorded reference
   position.
6. To convince yourself it works, drop the thresholds to 1–2° in Settings, re-arm, and nudge
   the dish.

### Known limits

- The phone must stay on the dish's Wi-Fi to poll it. This is a "phone lives at the site"
  alarm, not a remote-monitoring service — use the webhook for off-site alerting.
- If the thief cuts power or takes the dish out of Wi-Fi range, polling simply stops. By
  design this does **not** sound the alarm, since brief dropouts and reboots are common; the
  dashboard and history show the dish as unreachable instead.
- GPS coordinates are frequently unavailable (see above). Orientation still works.
- Aggressive OEM battery managers (Xiaomi, Samsung, Huawei) may kill the service regardless of
  the battery-optimisation exemption. Whitelist the app in the vendor's own settings too.

## Layout

```
core/   pure Kotlin/JVM — protobuf wire codec, gRPC-over-OkHttp client, theft detector
app/    Android — foreground service, alarm, Compose UI, settings, event log, webhook
```
