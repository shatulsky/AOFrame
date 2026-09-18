# AOFrame server

Optional companion admin server. Not required to run AOFrame at all - the
app talks to Immich/OpenMeteo directly and exposes its own on-device
`LocalControlServer` regardless of whether this is running.

Two things it does:

1. **Proxies the app's `LocalControlServer`** (`routes/frame-actions.js`) -
   night-mode/countdown/slideshow-settings/status/etc. - for remote
   editing from a phone/laptop instead of curling the device directly.
   Also backs the basic dashboard UI at `/`.
2. **Hosts the CRUD photo API** (`routes/photos.js`) that backs the app's
   `photoSource: local` config - a fully self-hosted alternative to
   Immich. Upload photos/videos here; the app polls this server's
   `/api/photos` instead of an Immich instance.

## Quick start

```
cp config.example.json config.json   # fill in frameControlUrl, etc.
docker compose up -d --build
```

Then set the app's `immich-secrets.json`:

```json
{ "photoSource": "local", "localServerBaseUrl": "http://<this-host>:8080" }
```

(`baseUrl`/`apiKey` aren't needed at all in this mode.)

No authentication - LAN-only via `config.json`'s `ipWhitelist`, same
posture as the on-device `LocalControlServer` it proxies. Don't expose
this to the internet.

*A fuller setup guide, the `LocalControlServer` API reference, and a dev
walkthrough are coming in this repo's docs pass - this file is a
placeholder covering just enough to run it today.*
