# pi-video-gate - install/verify/revert

Standalone sidecar service, sitting between Immich and the frame. Three jobs:

1. **Video-ready gate** (`GET /video/:id`) - the frame fetches video through this
   instead of hitting Immich's `/api/assets/{id}/video/playback` directly. Only
   ever serves a file once a real transcoded copy exists on disk; a plain 404
   otherwise, which the frame's own retry-via-periodic-sync logic handles.
2. **fps normalization** - a second Immich `AssetCreate` webhook (separate from
   the frame's existing one) triggers this service to check any new video's
   frame rate and re-encode to 30fps if needed, outside Immich entirely (Immich
   itself has no fps-cap option at any settings level).
3. **Webcam clip capture** (`GET /webcam/clips`, `GET /webcam/clip/:id`) - grabs a
   fresh ~30s clip from each configured live webcam every 30 minutes (skipping
   the whole cycle while the frame is in its night-mode sleep window, if
   `frameControlUrl` is configured) and serves
   them for the app's own `WebcamClipSync` to mix into the normal slideshow
   rotation. Plain `-c copy`
   (stream-copy, no re-encode) - this workload is network-bound, not CPU-bound, so unlike
   job 2 above this needs no `nice`/`-threads` throttling. Master on/off switch
   at `GET`/`POST /webcam/enabled` (`webcam-enabled.json`, defaults to enabled) -
   when off, the scheduled/forced capture cycles do nothing, `/webcam/clips`
   returns an empty manifest, and `/webcam/clip/:id` 404s regardless of what's
   still cached on disk - the single flag the whole feature checks, see that
   endpoint's own comment in `server.js`.

## Prerequisites

- Node.js (developed against Node 22; any reasonably recent version should work).
- `ffmpeg`/`ffprobe` on the **host** directly (not just inside Immich's
  container) - `which ffmpeg` to confirm.
- `docker` CLI access for whichever user runs this, if deploying via Docker
  (see docker-compose.yml).
- An Immich API key with admin scope (needed for `/api/system-config`-adjacent
  admin calls like the job trigger) - create one via Immich's web UI, **Account
  Settings -> API Keys -> New API Key**. Can reuse the same key already
  provisioned for the native app's `immich-secrets.json` if you'd rather not
  create a second one - it already has the needed scope.

## Install

1. Copy `config.example.json` to `config.json` and fill in your own values
   (`apiKey`, `libraryDir`, and optionally `webcams`/`frameControlUrl`/
   `deployHost`/`deploySshKey`).
2. If deploying to a remote host over SSH, run `./deploy.sh` (reads
   `deployHost`/`deploySshKey` from config.json - see that script's own header
   comment). It copies the code, runs `npm install` if needed, and warns if
   `config.json` isn't present on the remote host yet (it never copies
   `config.json` itself, since it's a secret). Otherwise, copy the directory
   over by whatever means you prefer and run `npm install` there directly.
3. Build and start the container (one-time; `deploy.sh` re-runs this
   automatically on later code changes):
   ```
   docker compose up -d --build
   ```
   `docker-compose.yml` uses `network_mode: host` (so `config.json`'s
   `http://localhost:2283` keeps resolving to Immich's published port) and
   bind-mounts the whole working directory into the container rather than
   baking code/config into the image - see that file's own header comment.
   `restart: unless-stopped` means it survives a host reboot.
4. Register a second Immich workflow (leave the frame's existing
   `AssetCreate` -> `http://<your-frame-ip>:8099/action/immich-webhook` workflow
   untouched - this is a separate, additional one):
   ```
   POST http://<your-immich-host>:2283/api/workflows
   {
     "trigger": "AssetCreate",
     "steps": [{
       "method": "immich-plugin-core#webhook",
       "config": { "url": "http://<this-service-host>:8090/immich-webhook", "method": "POST" }
     }]
   }
   ```
   `GET /api/workflows/{id}` afterward to confirm it actually saved (the create
   response doesn't echo the `steps` array back).
5. Update the frame's video-fetch URL to point at this gate instead of Immich
   directly (the app's `AssetCacheSync.kt`/`ImmichClient.kt`, via
   `immich-secrets.json`'s `videoGateBaseUrl`), rebuild, reinstall.

## Verify

- `curl http://<host>:8090/video/<a real cached asset id>` - should
  return video bytes (200) if that asset already has an `encoded-video` file,
  or a plain 404 if not.
- Upload a real >30fps test video through Immich; watch the service's logs
  (`docker logs pi-video-gate -f`) for the `[webhook]` raw-body line
  (confirms the actual payload shape) and the `[process]`
  lines tracking fps detection, normalization, and replacement.
- Confirm via `docker exec <immich-postgres-container> psql ...` (or the web
  UI) that the original asset is gone and a new one exists with the same
  content at 30fps.
- Webcam clips: watch the logs for `[webcam] <id> refreshed` lines (first one
  fires immediately on service start, then every 30 min). `curl
  http://<host>:8090/webcam/clips` should list the configured camera ids
  once at least one successful grab has happened; `curl -o test.mp4
  http://<host>:8090/webcam/clip/<id>` should download a playable ~30s
  clip (`ffprobe test.mp4`). Point one camera's `streamUrl` at something invalid
  and confirm its id drops out of `/webcam/clips` after `MAX_STALE` (3h) while
  the others keep refreshing normally. If `frameControlUrl` is configured,
  enable night mode with a wide sleep window and confirm the log shows `frame
  is asleep - skipping this cycle entirely` with no `ffmpeg`/webcam log lines
  during that window. `curl -X
  POST -H 'Content-Type: application/json' -d '{"enabled":false}'
  http://<host>:8090/webcam/enabled` then confirm `/webcam/clips`
  returns `{"clips":[]}`, `/webcam/clip/<id>` 404s even for a still-cached
  clip, and a forced `POST /webcam/refresh` returns `{"status":"disabled"}`
  without any new `[webcam]` log lines - re-enable the same way afterward.

## Revert / uninstall

```
docker compose down
rm -rf <this deployed directory>
```
Then `DELETE /api/workflows/{id}` for the second workflow created in step 4
above (leave the frame's own original workflow alone), and revert the app's
video-fetch config back to hitting Immich directly.
