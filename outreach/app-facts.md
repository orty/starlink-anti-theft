# What Starlink Guard actually does

Written from the app branch (`claude/starlink-anti-theft-app-4h3s2o`) so replies
describe the real thing. Package `orty.starlink_guard`, free.

## The mechanism

The phone joins the dish's Wi-Fi and polls the dish's own gRPC endpoint at
`192.168.100.1:9200` every 10 seconds, reading the azimuth, elevation and mast
tilt the dish reports about itself — plus GPS where the plan and settings expose
it. Arming records a reference position. If the dish moves off it, the phone
sounds an alarm.

- Plays on the **alarm stream**, so silent and vibrate do not mute it; it can
  lift Do Not Disturb.
- Motorised re-aiming is ignored (orientation is only judged while the actuators
  are idle), and a breach must repeat across two polls. That is what makes it
  quiet enough to leave armed.
- **Loss of contact** is a separate trigger, off by default: the dish going
  silent for 15s fires the alarm.
- Home-screen widget arms, disarms and silences in one tap.
- **Webhook** POSTs a JSON alarm payload — Home Assistant, n8n, Zapier — which
  is the only way to hear about it off-site.

## The limits, which belong in the replies

- **The phone must stay on the dish's Wi-Fi.** This is a "phone lives at the
  site" alarm, not remote monitoring. Someone asking about a dish they leave
  behind needs a spare phone on site plus the webhook.
- **It does not track or recover anything.** No GPS trail, no find-my-dish.
  Once the dish is gone it is gone; the app is there to make noise while the
  theft is happening.
- GPS is often unavailable — `PERMISSION_DENIED` unless enabled in the official
  app, and absent on many plans. Orientation is the primary signal.
- Aggressive OEM battery managers can kill the service.

## Hardware differences that change the answer

- **Gen2 / Gen3** — router is a separate indoor unit. Steal the dish and the
  Wi-Fi keeps serving while `192.168.100.1` goes quiet. That is the default
  signature.
- **Mini** — the router is inside the dish. Unplug it and the Wi-Fi goes too,
  so the network vanishing *is* the theft signal. Two opt-in switches cover it,
  at the cost of the alarm firing when you walk out of range.

## Two things to settle before posting

- The Play listing has to actually be public. `docs/PLAY_RELEASE.md` describes
  creating it; an internal-testing listing 404s for everyone else.
- That doc also flags the naming question — "Starlink" in the app name sits on
  nominative-use ground. Posting to r/Starlink is the moment it goes public.
