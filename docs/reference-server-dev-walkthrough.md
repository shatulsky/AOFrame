# Reference server: how it's built

A walkthrough of [`../server/`](../server/)'s design, for anyone
extending it or building their own admin tool against the same on-device
API instead.

## What it talks to

Everything the reference server does ultimately reduces to one of two
things:

1. **Proxying `LocalControlServer`** — the app's own on-device HTTP
   server (NanoHTTPD, port 8099). See
   [`local-control-server-api.md`](local-control-server-api.md) for the
   full endpoint reference. The device is the only source of truth for
   this data; the reference server holds no state of its own here.
2. **Owning the CRUD photo store** — plain files on disk plus a JSON
   manifest, for `photoSource: local` mode. This is the one place the
   reference server *is* the source of truth, since there's no device-side
   equivalent to proxy.

## The request/response cycle for a proxied action

Take `POST /action/night-mode` as an example, end to end:

1. A phone/laptop browser (or `curl`, or an HA `rest_command`) sends
   `POST http://<server-host>:8080/action/night-mode` with a JSON body.
2. `lib/ipWhitelist.js` checks the request's source IP against
   `config.json`'s `ipWhitelist` — this is the *only* access control in
   front of any of this, see "Security posture" below.
3. `routes/frame-actions.js`'s generic proxy handler forwards the same
   method and body to `http://<frame-ip>:8099/action/night-mode` (the
   real device), via `lib/frameClient.js`'s thin `http` wrapper.
4. The device's own `LocalControlServer` saves the new config and echoes
   it back as its response.
5. The reference server relays that response back to whoever called it,
   unmodified.

If the device is unreachable at step 3, the proxy returns `502` with a
JSON `{"error": "..."}` body rather than hanging — every route follows
this same fail-clearly pattern, so a dashboard/automation calling this
API always gets a definite answer quickly, never a silent timeout.

**There's no separate config store on the reference-server side for any
of this** — restart the reference server mid-session and nothing is
lost, because it never held the state to begin with. This is a
deliberate simplicity choice: the device already persists its own config
reliably, so duplicating that here would just be a second thing that
could drift out of sync with the first.

## The CRUD photo store

`routes/photos.js` is the one part of this server that owns real state:
uploaded files live under `config.json`'s `photoStorageDir`, keyed by a
generated UUID, with a single `manifest.json` sidecar file tracking each
id's original filename/extension/MIME type/upload timestamp. No database
— a flat JSON manifest plus files on disk is simple, human-inspectable,
and entirely sufficient at the scale this is meant for (a personal photo
frame's library, not a multi-tenant service).

The app's own `LocalAssetSync` (on the Android side) polls
`GET /api/photos` for the current list and downloads each file by id,
following the exact same "evict what's gone, download what's missing"
pattern as its Immich-mode counterpart — see
[`app-architecture.md`](app-architecture.md) for why that pattern exists.

Endpoints:

- `GET /api/photos` — list everything: `[{id, originalName, ext, mimeType, uploadedAt, faceX?, faceY?}, ...]`.
- `POST /api/photos` — multipart upload, field name `photo`, one of
  `image/jpeg|png|webp|gif` or `video/mp4`. Returns the same shape as one
  list entry.
- `GET /api/photos/:id` — download the raw file (what `LocalAssetSync`
  actually fetches).
- `PATCH /api/photos/:id/face` — JSON body `{faceX, faceY}`, each 0-100,
  top-left origin. Sets a manual Ken Burns face target for an IMAGE asset
  — this mode has no automatic face detection of its own (no equivalent
  data source to Immich's), so this is the only way an IMAGE asset gets
  one. Video/Live-Photo assets ignore it; there's no pairing concept for
  Live Photos in local mode either.
- `DELETE /api/photos/:id` — removes the file and its manifest entry.

Photos pass through untouched (no resizing/re-encoding). Videos don't -
every upload is transcoded to 8-bit SDR H.264 (plain `libx264`/`-r 30`,
the same shape as `pi-video-gate`'s own fps-normalization command) before
it's ever stored. This was added after confirming some phone HDR/10-bit
exports (H.264 High 10 or HEVC Main10, BT.2020/HLG color) decode their
audio track fine but never produce a visible video frame on this app's
target hardware - reproduced identically on both the emulator's software
decoder and the frame's Rockchip hardware decoder. A video that ffmpeg
can't process at all is rejected with `400` rather than stored broken.

## Security posture — call this out explicitly if you extend it

**Neither the reference server nor the device's own `LocalControlServer`
has any authentication.** The only access control is
`lib/ipWhitelist.js`'s LAN allowlist (`config.json`'s `ipWhitelist`,
defaulting to loopback-only until you configure it for your LAN). This
was a deliberate choice for a reference deployment meant to run entirely
on a trusted home LAN — **do not port-forward either service to the
internet**, and don't assume the allowlist alone is a substitute for
real authentication if you're deploying this somewhere less trusted than
a home network. If you need remote access, put it behind a VPN rather
than exposing the port directly.

## Extending it: adding a new proxied action

If the app grows a new `LocalControlServer` endpoint, wiring it into the
reference server is mechanical — add its path to the right array in
`routes/frame-actions.js` (config-style GET+POST, fire-and-forget
POST-only, or read-only GET-only, matching the three groups already
there) and it's automatically proxied with the same error handling as
every other route. No new file, no new logic — this is deliberate, since
every route in that file really is a 1:1 pass-through with no
route-specific behavior of its own.
