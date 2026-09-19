# LocalControlServer API reference

The app runs a small local HTTP server on-device (NanoHTTPD, port `8099`)
exposing every remote-config/status action it supports. The
[reference server](../server/) is a thin proxy over this same API for
convenience — this is the actual source of truth either way; the device
owns its own config, nothing else does.

**No authentication.** This is LAN-only by design — do not expose port
`8099` to the internet. Put it behind a VPN if you need remote access.

Config-style endpoints (`night-mode`, `countdown`, `slideshow-settings`,
`webcam-test-mode`) all follow the same shape: `GET` returns the current
config as JSON, `POST` (JSON body) saves a new one and echoes it back.

## `GET`/`POST /action/night-mode`

Schedules a daily sleep/wake window (display power off/on, not just the
app pausing).

```json
{ "enabled": true, "sleepTime": "01:00", "wakeTime": "08:00" }
```

## `GET`/`POST /action/countdown`

An optional on-screen countdown to a target date/time.

```json
{ "enabled": true, "targetDate": "2027-10-02T00:00", "label": "Trip", "precision": "full" }
```

`precision` is one of `full` (down to seconds), `daysHours`, or
`daysOnly`.

## `GET`/`POST /action/slideshow-settings`

Per-photo duration and the Ken Burns pan/zoom's end-of-animation zoom
level.

```json
{ "durationMs": 6000, "endZoom": 1.5 }
```

Values are clamped to sane bounds on save — an out-of-range POST is
silently clamped, not rejected.

## `POST /action/refresh-cache`

Fire-and-forget: triggers an immediate full re-sync against your
configured photo source (Immich, or the local reference server in
`photoSource: local` mode). Returns immediately with
`{"status": "refreshing"}` rather than waiting for the sync to finish.

## `POST /action/reset-faces`

Clears every cached asset's face-detection result, then triggers the same
refresh as above. Use this if you've enabled face detection in Immich
*after* assets were already synced, so the whole library gets
re-evaluated under the current face-detection state rather than reusing
stale "no face found" results.

## `POST /action/reshuffle`

Reorders the current photo rotation on demand, without touching what's
already cached/synced — never talks to your photo source at all, purely
local reordering.

## `POST /action/immich-webhook`

Point your photo source's asset-created webhook here
(`http://<device-ip>:8099/action/immich-webhook`) so a new upload
triggers a sync immediately, rather than waiting for the next periodic
pass. Fire-and-forget, same shape as `refresh-cache` — the payload itself
is ignored, since a full re-sync + diff happens either way.

## `GET`/`POST /action/webcam-test-mode`

Debug-only "show only webcam clips" switch, useful for previewing webcam
integration without waiting for photos to cycle around to a webcam clip
naturally.

```json
{ "enabled": true }
```

Unlike the config endpoints above, flipping this one is deliberately
disruptive — it immediately restarts the slideshow to show the effect
right away, rather than waiting for the next natural transition.

## `POST /action/refresh-webcam`

Fire-and-forget: re-syncs webcam clips only, without touching your photo
source or interrupting whatever's currently showing.

## `GET /action/webcam-sync-status`

Read-only. Per-camera "last synced to this device" timestamps:

```json
{ "front-porch": 1735689600000, "backyard": 1735689612000 }
```

## `GET /status`

Read-only. General device/library status:

```json
{
  "lastSyncAt": 1735689600000,
  "assetCount": 131,
  "photoCount": 102,
  "videoCount": 28,
  "livePhotoCount": 1,
  "cacheBytes": 256058869,
  "facesFound": 74,
  "facesNone": 28,
  "facesPending": 0,
  "screenAwake": true
}
```

`screenAwake` reflects the display's real power state
(`android.os.PowerManager.isInteractive()`), not the night-mode
schedule's inferred window — a manual power toggle, an external
sleep/wake call (e.g. from a home-automation integration), or the
scheduled night-mode timer below are all reflected here immediately.
Useful for any external caller that wants to skip a redundant
sleep/wake call rather than blindly firing one.
