# AOFrame setup guide

End-to-end setup: from a rooted Frameo-based digital photo frame to a
working AOFrame install. Each numbered step is independent enough to
stop after if you don't need what comes next — the app alone (steps 1–3)
is a complete, working setup on its own.

## What you'll end up with

- The AOFrame Android app installed on your frame, showing your photo
  library (via Immich, or fully self-hosted via the optional reference
  server — see step 5) with weather, clock, and an optional countdown.
- *(Optional)* `pi-video-gate`, a small sidecar that fixes two real
  Immich/video playback issues on constrained hardware (see its own
  [INSTALL.md](../pi-video-gate/INSTALL.md)).
- *(Optional)* The reference server, for remote-editing the app's
  settings from a phone/laptop instead of curling the device directly,
  and/or running fully without Immich.

## Prerequisites

- A Frameo-based digital photo frame with ADB access and root — see
  [`hardware-and-root.md`](hardware-and-root.md) if you haven't gotten
  this far yet. This is almost always achievable on Frameo hardware, but
  the exact toggles/USB modes vary by model.
- Android Studio (for building the app) — a recent stable version, with
  its bundled JDK (no separate JDK install needed).
- *(Optional, for `photoSource: immich` mode)* A running
  [Immich](https://immich.app/) instance on your network, and an API key
  for it (Immich web UI → Account Settings → API Keys → New API Key). See
  [`immich-api-notes.md`](immich-api-notes.md) for API details this app
  relies on.
- *(Optional, for `pi-video-gate`/the reference server)* Docker, on
  whatever host you want to run them on (they don't need to run on the
  frame itself, and don't need to be the same host as Immich).

## 1. Get ADB access to your frame

See [`hardware-and-root.md`](hardware-and-root.md) in full. In short:
enable Frameo's ADB toggle (Settings → About → Beta Program → ADB
Access), and if the frame still doesn't enumerate as a USB device, look
for a separate "USB storage/transfer" toggle — this two-toggle
requirement is common on Frameo-based hardware.

Confirm before continuing:
```
adb devices
```
should show your frame as `device`, not `unauthorized` or absent.

## 2. Set up Immich (skip if you're going straight to local/self-hosted mode)

Point the app at your existing Immich instance, or install one — see
[Immich's own docs](https://immich.app/docs/overview/introduction) for
that part, it's out of scope here. Once it's running:

1. Create an API key (Account Settings → API Keys → New API Key).
2. Note your Immich instance's LAN URL (e.g. `http://192.168.1.50:2283`).

## 3. Configure and build the app

```
cd app
cp immich-secrets.example.json immich-secrets.json
```

Fill in `immich-secrets.json` with your own values — see the comments in
`immich-secrets.example.json` for what each field does. At minimum, for
Immich mode: `baseUrl`, `apiKey`, `frameAddr` (your frame's ADB address,
e.g. `192.168.1.60:5555`). Everything else (weather coordinates, locale,
video-gate/reference-server URLs) is optional and degrades gracefully
when unset — see each feature's own section below.

**Build and install:**

```
./release.sh
```

This builds the release APK, installs it on your frame over network ADB,
provisions `immich-secrets.json` onto the device, and launches the app.
See the script's own header comment for exactly what it does and why.

Alternatively, open `app/` in Android Studio and run/install normally if
you'd rather iterate from the IDE — `release.sh` is a convenience for a
repeatable full build+deploy, not the only way to get the app onto a
device.

### Verify

- The app should launch full-screen and start syncing your Immich
  library within a few seconds (a loading spinner shows until the first
  photo is ready to display).
- `curl http://<frame-ip>:8099/status` should return sync/asset counts —
  see [`local-control-server-api.md`](local-control-server-api.md) for
  the full on-device API this exposes.

### Feature-by-feature: what's optional and how it degrades

- **Weather widget**: hidden entirely if `weatherLatitude`/
  `weatherLongitude` aren't set. If only `weatherSeaLatitude`/
  `weatherSeaLongitude` are missing, the sea-temperature line is hidden
  but the land forecast still shows.
- **Countdown**: off by default — configure via
  `POST http://<frame-ip>:8099/action/countdown` (or the reference
  server's dashboard, see step 5) once the app is running.
- **Night mode** (scheduled sleep/wake): off by default, same
  config-endpoint pattern as countdown.
- **Locale**: defaults to English. Set `"locale"` to a BCP 47 tag (e.g.
  `"uk"`) in `immich-secrets.json` for a supported translation, or leave
  it unset/unrecognized to fall back to English automatically.

## 4. (Optional) `pi-video-gate` — smoother video playback

Fixes two real issues with serving video directly from Immich on
constrained hardware (a video-readiness race, and no fps cap in Immich's
own transcode settings). See its own
[INSTALL.md](../pi-video-gate/INSTALL.md) for the full setup — it's a
small standalone Docker service, independent of the reference server
below.

## 5. (Optional) The reference server — remote editing and/or local mode

A small companion admin server: proxies the app's on-device API for
remote editing from a phone/laptop, and/or backs a fully self-hosted
`photoSource: local` mode that needs no Immich instance at all. See
[`../server/README.md`](../server/README.md) for setup.

If you just want local mode without Immich:

```json
{ "photoSource": "local", "localServerBaseUrl": "http://<server-host>:8080" }
```

in `immich-secrets.json` — `baseUrl`/`apiKey` aren't needed at all in
this mode. Upload photos/videos via the reference server's dashboard (or
its `/api/photos` API directly); the app polls that instead of Immich.
Face-targeted Ken Burns and Live Photo pairing aren't available in this
mode (there's no equivalent data source for either), but everything else
— weather, countdown, night mode, webcam clips — works identically to
Immich mode.

## 6. (Optional) Performance/persistence scripts

- [`../scripts/adbtcp/`](../scripts/adbtcp/) — makes network ADB survive
  a reboot, so you don't need to reconnect via USB and re-run
  `adb tcpip 5555` every time.
- [`../scripts/cpu-governor/`](../scripts/cpu-governor/) — a CPU governor
  tweak that reduced thermal throttling significantly on the Rockchip
  reference hardware this was developed against; worth trying if your
  frame's SoC throttles under sustained load (see
  [`hardware-and-root.md`](hardware-and-root.md)).

Both are optional, independent, and fully reversible — see each one's own
`INSTALL.md`.

## Where to go next

- [`local-control-server-api.md`](local-control-server-api.md) — every
  endpoint the app exposes, if you want to build your own admin tooling
  instead of using the reference server.
- [`immich-api-notes.md`](immich-api-notes.md) — Immich API quirks this
  app's code already works around, useful if you're modifying
  `ImmichClient` or building something similar yourself.
- [`hardware-and-root.md`](hardware-and-root.md) — hardware-level facts
  and gotchas for Frameo-based frames generally.
- [`performance-notes.md`](performance-notes.md) — concrete before/after
  findings from tuning the app for smooth playback on constrained,
  passively-cooled hardware (bitmap formats, animation fps-capping,
  overdraw, thermal throttling).
- [`app-architecture.md`](app-architecture.md) — why the app is built the
  way it is (plain Views over Compose, ExoPlayer, NanoHTTPD, plain
  SQLite, scheduling approach, night-mode implementation) — useful if
  you're extending it or building something similar.
- [`reference-server-dev-walkthrough.md`](reference-server-dev-walkthrough.md) —
  how the optional reference server is built, if you want to extend it
  or write your own admin tool against the same API.
- [`home-assistant-integration.md`](home-assistant-integration.md) —
  wiring the app's status/actions into Home Assistant as sensors and
  callable scripts.
