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
Photo bytes never pass through any additional processing here (no
resizing/re-encoding) — whatever you upload is exactly what the app
downloads and decodes on-device.

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
