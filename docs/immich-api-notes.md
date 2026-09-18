# Immich API — notes from building AOFrame

Confirmed by hand against a real Immich instance while building this
app's `ImmichClient`. Immich's public docs don't cover everything below
in one place — some of this was found through validation-error messages
or trial and error, not official documentation. Treat this as a
supplement to Immich's own API docs, not a replacement.

## Auth

Every request needs an `x-api-key` header. Generate a key via the Immich
web UI: **Account Settings → API Keys → New API Key**. A key inherits the
permissions of the user who created it (an admin-created key can hit
admin-only endpoints).

**Never commit a real key** — see this repo's `immich-secrets.example.json`
for the gitignored-config pattern this app uses.

## Listing / searching assets

`POST /api/search/metadata` — body is `{"size": <pageSize>, "page": <pageNum>, ...filters}`.

Two gotchas:
1. **`nextPage` in the response is a string** (e.g. `"2"`) but the
   request body's `page` field must be a **number**. Passing the string
   straight back on the next loop iteration 400s with
   `"Invalid input: expected number, received string"` — always
   `Number(nextPage)`.
2. **Asset dimensions are plain `item.width` / `item.height`** on each
   returned item — not nested under `item.exifInfo.exifImageWidth/Height`
   as might seem more natural to assume. Getting this wrong doesn't
   error, it just silently returns `undefined`.

Useful filters: `"type": "IMAGE"`/`"VIDEO"` to restrict by asset type,
`"albumIds": ["<uuid>"]` to restrict to specific albums,
`"personIds": ["<uuid>"]` to restrict to a specific clustered person.

Each returned asset also carries `isTrashed`, `isArchived`,
`livePhotoVideoId` (non-null on a still with a paired Live Photo motion
clip), and `people` (see Faces below — don't trust this field for raw
face data).

## Fetching asset bytes — pick the right size

`GET /api/assets/{id}/thumbnail?size=<size>` and
`GET /api/assets/{id}/original`.

| `size=` | Use case | Typical cost |
|---|---|---|
| `thumbnail` | A deliberately tiny variant, useful for a stretched-back-up "free" blur effect | Smallest |
| `preview` | Normal on-screen display, capped around screen resolution | Moderate |
| *(none, `/original`)* | Avoid for on-screen display | Largest — a real camera original can be very large, and fetching it every time causes needless bandwidth/decode cost with no visible benefit |

Immich sends real caching headers (`Cache-Control`, `ETag`,
`Last-Modified`) on these responses — if you're proxying them through
your own server, forward those headers through unmodified so clients get
free caching rather than re-fetching every time.

## Faces — raw detections vs. the `people` field

**The asset's own `people` field (from `/api/search/metadata` or
`GET /api/assets/{id}`) only shows named/confirmed people** — an asset
can be findable via a `personIds` search while its own `people` array is
still `[]`. This looks like deliberate UI-curation gating (unconfirmed ML
guesses aren't surfaced in normal listings), not a bug.

**For raw face bounding boxes regardless of naming/confirmation status,
use `GET /api/faces?id={assetId}`** instead:

```json
{
  "id": "...",
  "boundingBoxX1": 456, "boundingBoxY1": 863,
  "boundingBoxX2": 504, "boundingBoxY2": 919,
  "imageWidth": 1080, "imageHeight": 1920,
  "sourceType": "machine-learning",
  "person": { "id": "...", "name": "", ... }
}
```

This app uses the largest face's center (as a percentage of image
dimensions) as a Ken Burns pan/zoom target, falling back to plain center
framing when the array is empty.

### Getting face data to actually exist

Enabling the machine-learning container does **not** retroactively
process an existing library — new uploads get queued automatically going
forward, but existing assets need a manual nudge:

```
PUT /api/jobs/faceDetection   -d '{"command":"start","force":true}'   # raw bounding boxes
PUT /api/jobs/facialRecognition -d '{"command":"start","force":true}' # clusters them into named people
```

(`POST` to these paths 404s — it's `PUT`. Check progress via
`GET /api/jobs`, which returns per-job-type `queueStatus`/`jobCounts`.)

`facialRecognition.minFaces` (in system-config, default `3`) blocks
Person formation for small/diverse libraries — with mostly one appearance
per face, nothing clusters until this is lowered (e.g. to `1`). This only
affects the `people` field / named clustering, not the raw `/api/faces`
data — but the job still needs to have *run* for `/api/faces` to return
anything at all.

## Machine learning / system-config

`GET`/`PUT /api/system-config` — full config object; `PUT` expects the
whole thing back (fetch, mutate the one field you care about, `PUT` the
whole object). The `machineLearning` section:

```json
{
  "machineLearning": {
    "enabled": true,
    "clip": { "enabled": true },
    "facialRecognition": { "enabled": true, "minFaces": 3 },
    "ocr": { "enabled": true },
    "duplicateDetection": { "enabled": true }
  }
}
```

**Starting the `immich-machine-learning` container flips all of these to
`enabled: true` at once**, even ones you didn't ask for — fetch the
config immediately after starting the container and explicitly disable
anything you don't want, same PUT-the-whole-object pattern.

## Live Photos

An image asset carries `livePhotoVideoId` pointing at its paired
motion-clip asset.

- **Uploading a Live Photo's still and its paired clip as two separate
  `POST /api/assets` calls does NOT auto-pair them**, even with intact
  metadata on both files. Automatic pairing appears specific to Immich's
  mobile-app camera-roll upload flow, not a plain REST upload of two
  independently-sourced files.
- **Manual linking works**: `PUT /api/assets/{stillId} -d '{"livePhotoVideoId": "<videoAssetId>"}'`
  sets the pairing directly and immediately.
- **A linked Live Photo's paired clip is NOT excluded from a plain
  `POST /api/search/metadata` search with `"type": "VIDEO"`** — it shows
  up like any standalone video. Anything building a video list from
  search results needs to explicitly filter out ids that match a still's
  `livePhotoVideoId`, rather than assuming Immich already excludes them.

## Uploading assets

`POST /api/assets`, multipart form: `deviceAssetId`, `deviceId`,
`fileCreatedAt`, `fileModifiedAt`, `assetData` (the file). Response is
`{"id": "...", "status": "created"}` for a genuinely new asset, or
`{"status": "duplicate", "id": "<existing-id>"}` if the file's checksum
already exists — **dedup is checksum-based, not filename-based**.

## Deleting / trashing assets

`DELETE /api/assets -d '{"ids": [...], "force": true|false}'`.

- **`"force": true`**: permanent deletion.
- **Without `force` (or `false`)**: soft-delete to trash — the asset is
  still fully fetchable via the API, `GET /api/assets/{id}` shows
  `"isTrashed": true`. No `/api/trash/statistics` endpoint exists to
  check trash size directly.
- **Restore via `POST /api/trash/restore/assets`** (not
  `/api/trash/restore` — that bare path 404s), body `{"ids": [...]}`.
- **No endpoint to list what's in the trash** — use
  `POST /api/search/metadata` with a `trashedAfter` filter (e.g.
  `"trashedAfter": "1970-01-01T00:00:00.000Z"` to include everything ever
  trashed) instead.

## Webhooks (via the "workflow" system)

Immich's webhook isn't a standalone setting — it's a step inside a
"workflow":

```
POST /api/workflows
{
  "trigger": "AssetCreate",
  "steps": [{
    "method": "immich-plugin-core#webhook",
    "config": { "url": "http://<target>:<port>/<path>", "method": "POST" }
  }]
}
```

- Valid `trigger` values (from a validation-error enum, not docs):
  `AssetCreate`, `AssetMetadataExtraction`.
- **The `POST /api/workflows` response doesn't echo the `steps` array
  back** — always `GET /api/workflows/{id}` afterward to confirm what
  actually saved.
- `GET /api/workflows` lists all configured workflows;
  `DELETE /api/workflows/{id}` removes one.

### The Docker-networking gotcha

**If the receiving endpoint is on the same host as Immich but Immich runs
in Docker, the webhook call arrives from Immich's *container* IP, not the
host's LAN IP** — even though the webhook URL you configured points at
the LAN IP. If a LAN-only IP allowlist rejects an incoming webhook with
an unfamiliar IP, check the Docker bridge subnet
(`docker network inspect <compose-project>_default | grep -i subnet`) and
whitelist that too.

## Video transcoding (`ffmpeg` section of system-config)

Relevant fields on a recent Immich version: `crf`, `threads`, `preset`,
`targetVideoCodec`, `acceptedVideoCodecs`, `targetAudioCodec`,
`acceptedAudioCodecs`, `acceptedContainers`, `targetResolution`,
`maxBitrate`, `bframes`, `refs`, `gopSize`, `temporalAQ`, `cqMode`,
`twoPass`, `preferredHwDevice`, `transcode`, `accel`, `accelDecode`,
`tonemap`, `realtime`. **No frame-rate/fps cap field exists in this
schema** as of this writing.

**`transcode: "required"` only re-encodes a source whose codec/container
isn't in `acceptedVideoCodecs`/`acceptedContainers`** — it does NOT
re-evaluate an already-accepted codec against `targetResolution`/
`maxBitrate` at all, no matter how oversized the source is. Use
`"optimal"` if you want existing-but-oversized accepted-codec content
re-encoded down to your target settings.

**H.264 Level is a direct function of resolution × frame-rate,
independent of `maxBitrate`.** Macroblocks/sec = `(width × height / 256) × fps`;
Level 3.1's cap is 108,000, Level 3.2's is 216,000. Raising `maxBitrate`
alone does not fix a Level problem — only lowering `targetResolution`
does (or capping fps, which this schema doesn't support). Check your
actual decoder's real capability (`/vendor/etc/media_codecs.xml` on the
device, or a `MediaCodecList` query from a native app) rather than
assuming a browser-based playback ceiling transfers to a native
`MediaCodec` caller on the same hardware — they can differ substantially,
since a browser engine's own overhead can dominate the real bottleneck on
constrained hardware, not the codec itself.

**A reverted transcode-policy config change does NOT retroactively fix
files already produced under the old settings.** Reverting `system-config`
only affects future transcode jobs — existing `encoded-video` output
files stay as they were until a job actually re-runs
(`PUT /api/jobs/videoConversion -d '{"command":"start","force":true}'`,
`force: true` required to reprocess assets that already have output).

**Verification workflow**:
`ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,profile,level,width,height,r_frame_rate,bit_rate -of default=noprint_wrappers=1 <file>`
against the real files under Immich's library volume (find it via
`docker inspect immich_server --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{println}}{{end}}'`),
both `upload/` originals and `encoded-video/` outputs. Cross-reference
active (non-trashed/non-archived) asset IDs via `/api/search/metadata` —
an `encoded-video` file with no matching active asset, or an active asset
with no `encoded-video` file at all, are both real states worth checking
for.

## Building a video pipeline around Immich: architecture lessons

Beyond the raw API shape above, a few things worth knowing if you're
building anything that sits between Immich and a playback client (like
[`pi-video-gate`](../pi-video-gate/)):

**Immich's video-ready endpoint doesn't wait for its own async transcode
job to finish** — it serves whatever exists on disk right now, falling
back to the raw original if no transcoded output exists yet. Combined
with a client that caches "first successful fetch, never recheck," this
creates a **permanent race**: if the client's first fetch happens to land
before transcoding finishes, it caches the raw (often oversized/wrong-fps)
original forever, with no natural retry. Any client caching from an
async-transcode backend needs an explicit "confirmed ready" gate — don't
cache on a plain 200, gate on the specific evidence that the *real*
processed output exists.

**Re-encoding a Live Photo's motion clip strips the Live Photo linking
metadata.** If your pipeline re-encodes video assets (e.g. an fps-cap
sidecar), re-pair via `PUT /api/assets/{stillId} -d '{"livePhotoVideoId": "<newVideoId>"}'`
after replacing the clip — this only works in that direction (setting the
link on the still, pointing at the video), not by any equivalent
operation at upload time.

**Order asset replacement as upload-then-delete, never delete-then-upload.**
If the upload half of a replace operation fails, delete-then-upload loses
the asset with no way back; upload-then-delete degrades to "briefly have
two copies," which is recoverable.

**A single-worker FIFO queue is a reasonable default for webhook-triggered
background jobs** if the sending side's retry/duplicate-delivery
semantics aren't clearly documented — it sidesteps having to reason about
concurrent processing of the same asset from a possible duplicate
delivery, at the cost of throughput (acceptable for a personal-scale
library).

**Isolate per-item failures in any batch sync loop.** A sync pass that
downloads N assets should never let one failing download abort the
remaining N-1 — wrap each item's work individually and log-and-continue,
otherwise a single transient failure (a not-yet-transcoded video, a
timeout) silently breaks everything scheduled after it in that pass.

**A poor-man's CPU throttle for a background transcode job sharing a host
with a latency-sensitive service**: `nice -n <priority>` plus explicitly
capping ffmpeg's `-threads` to fewer than the host's total core count,
when a dedicated tool like `cpulimit` isn't available or desired as a
dependency.

**Minor parsing gotcha**: `ffprobe`'s frame-rate field
(`r_frame_rate`, e.g. `"30000/1001"`) can arrive as a fraction string
with trailing whitespace/formatting quirks depending on ffprobe version
and output mode — validate before feeding it to a plain division/`Number()`
call rather than assuming a clean numeric string.

## Jobs (general)

`GET /api/jobs` — per-job-type queue status (`thumbnailGeneration`,
`metadataExtraction`, `faceDetection`, `facialRecognition`,
`smartSearch`, `duplicateDetection`, and others) with `jobCounts`
(`active`, `completed`, `failed`, `delayed`, `waiting`).
`PUT /api/jobs/{jobName} -d '{"command":"start","force":true}'` manually
(re)triggers a job type for the whole library — `force: true` needed to
reprocess assets that already have (or are assumed to already have) that
job's output.
