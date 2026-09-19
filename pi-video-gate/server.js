/* pi-video-gate - standalone sidecar service.
 * Solves two problems the frame's own code and Immich itself can't:
 *
 * 1. The frame's video fetch used to hit Immich's own
 *    /api/assets/{id}/video/playback directly, which never waits for
 *    Immich's async transcode job to finish - it silently falls back to
 *    serving the raw original if no transcoded file exists yet, and the
 *    frame's cache-once logic then never re-checks. This service gates
 *    that: it only ever serves a video once a real transcoded file
 *    exists on disk, otherwise a plain 404 (the frame's own retry-via-
 *    periodic-sync logic - see AssetCacheSync.kt - handles the rest).
 * 2. Immich hardcodes '-fps_mode passthrough' in its transcode command
 *    (confirmed via its compiled server code) - there is no fps cap
 *    anywhere in its config surface. This service normalizes any
 *    >30fps upload to 30fps itself, outside Immich, then replaces the
 *    asset via Immich's own public API (no source/internals patched).
 *
 * Deliberately a single-worker FIFO queue, not per-request handling -
 * a guard against duplicate/concurrent webhook delivery (Immich's own
 * workflow-webhook retry semantics aren't documented), and it matches
 * Immich's own videoConversion job concurrency:1 setting.
 */
const express = require("express");
const { execFile } = require("child_process");
const fs = require("fs");
const path = require("path");

const config = require("./config.json");

const IMMICH_BASE_URL = config.immichBaseUrl || "http://localhost:2283";
const API_KEY = config.apiKey;
const LIBRARY_DIR = config.libraryDir || "/home/pi/immich-app/library";
const PORT = config.port || 8090;
const WEBCAMS = config.webcams || [];
const FRAME_CONTROL_URL = config.frameControlUrl || "";

const MAX_ATTEMPTS = 3;
const RETRY_DELAY_MS = 8_000;
// 29.97 (NTSC) rounds to this - anything genuinely above 30 needs
// normalizing, anything at/under passes through untouched.
const MAX_ACCEPTABLE_FPS = 30.5;

const TMP_DIR = path.join(__dirname, "tmp");
fs.mkdirSync(TMP_DIR, { recursive: true });

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const WEBCAM_ID_RE = /^[a-z0-9-]+$/i;

const app = express();
app.use(express.json());

// --- A. Video-ready gate ---------------------------------------------

function findLibraryId() {
	const base = path.join(LIBRARY_DIR, "encoded-video");
	const dirs = fs.readdirSync(base, { withFileTypes: true }).filter((d) => d.isDirectory());
	if (dirs.length === 0) throw new Error("no library id directory found under encoded-video/");
	// Single-library personal instance - just take whichever one exists,
	// not hardcoded so this doesn't silently break if it's ever recreated.
	return dirs[0].name;
}

function encodedVideoPath(id) {
	const libraryId = findLibraryId();
	return path.join(LIBRARY_DIR, "encoded-video", libraryId, id.slice(0, 2), id.slice(2, 4), `${id}.mp4`);
}

// Confirmed-safe raw fallback: a
// small enough upload (already under Immich's targetResolution) gets
// skipped entirely by its "optimal" transcode policy - no encoded-video
// file is ever created for it, transcoded or not. Checking only
// encoded-video/ would 404 such a video forever, even though it's
// already fps-safe (our own webhook processing already confirmed that -
// see processAsset()'s "no normalization needed" branch). This map
// records "this asset id's raw original is confirmed fps-safe, and
// here's its host path" so the gate can serve it directly as a
// fallback when Immich itself chose not to transcode it.
const CONFIRMED_PATH = path.join(__dirname, "confirmed.json");

function loadConfirmed() {
	try {
		return JSON.parse(fs.readFileSync(CONFIRMED_PATH, "utf8"));
	} catch {
		return {};
	}
}

function recordConfirmedSafe(assetId, hostPath) {
	const map = loadConfirmed();
	map[assetId] = hostPath;
	fs.writeFileSync(CONFIRMED_PATH, JSON.stringify(map));
}

app.get("/video/:id", (req, res) => {
	const id = req.params.id;
	if (!UUID_RE.test(id)) return res.status(400).end();

	let encodedPath;
	try {
		encodedPath = encodedVideoPath(id);
	} catch (error) {
		console.error(`[gate] failed to resolve library id: ${error.message}`);
		encodedPath = null;
	}

	if (encodedPath && fs.existsSync(encodedPath)) {
		res.setHeader("Content-Type", "video/mp4");
		return fs.createReadStream(encodedPath).pipe(res);
	}

	const confirmedPath = loadConfirmed()[id];
	if (confirmedPath && fs.existsSync(confirmedPath)) {
		res.setHeader("Content-Type", "video/mp4");
		return fs.createReadStream(confirmedPath).pipe(res);
	}

	res.status(404).end();
});

// --- B. fps-normalization webhook + queue -----------------------------

const queue = [];
const queuedIds = new Set();
let processing = false;

// Confirmed against a real test upload's webhook (logged via
// the raw-body line below) - Immich's AssetCreate webhook body is
// {type, trigger, data: {asset: {id, type, livePhotoVideoId,
// originalPath, originalFileName, fileCreatedAt, fileModifiedAt, ...}}}.
// Crucially the payload already includes originalPath - for the common
// plain-VIDEO case this means no extra API/DB round-trip is needed at
// all to locate the source file (see processAsset() below).
function extractAsset(body) {
	return body?.data?.asset || null;
}

app.post("/immich-webhook", (req, res) => {
	console.log(`[webhook] raw body: ${JSON.stringify(req.body)}`);
	res.json({ status: "queued" });

	const asset = extractAsset(req.body);
	if (!asset?.id) {
		console.warn("[webhook] could not extract an asset from this payload shape - see raw body above");
		return;
	}
	enqueue(asset);
});

function enqueue(asset) {
	if (queuedIds.has(asset.id)) {
		console.log(`[queue] ${asset.id} already queued/in-flight - ignoring duplicate webhook`);
		return;
	}
	queuedIds.add(asset.id);
	queue.push(asset);
	processNext();
}

async function processNext() {
	if (processing) return;
	const asset = queue.shift();
	if (!asset) return;

	processing = true;
	try {
		await processAssetWithRetry(asset);
	} finally {
		queuedIds.delete(asset.id);
		processing = false;
		processNext();
	}
}

function sleep(ms) {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

// Retry-then-fail-open - after
// MAX_ATTEMPTS, log loudly and move on. No marker file, no gate-side
// distinction: a permanently-failed asset just means Immich's own
// already-independently-running transcode job eventually produces its
// own (un-normalized) encoded-video file, which the gate above will
// serve once it exists, same as anything else.
async function processAssetWithRetry(asset) {
	for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
		try {
			await processAsset(asset);
			return;
		} catch (error) {
			console.error(`[process] ${asset.id} attempt ${attempt}/${MAX_ATTEMPTS} failed: ${error.message}`);
			if (attempt < MAX_ATTEMPTS) await sleep(RETRY_DELAY_MS);
		}
	}
	console.error(
		`[process] ${asset.id} FAILED after ${MAX_ATTEMPTS} attempts - falling back to Immich's own transcode (unprotected fps)`
	);
}

async function processAsset(asset) {
	const isLivePhotoStill = asset.type === "IMAGE" && asset.livePhotoVideoId;
	if (asset.type !== "VIDEO" && !isLivePhotoStill) return;

	// The plain-VIDEO case (the common one) already has everything from
	// the webhook payload itself. A Live Photo's motion clip is a
	// separate, otherwise-hidden asset the payload doesn't describe -
	// that one case needs the Postgres path lookup as a fallback (the
	// asset API doesn't expose originalPath either way).
	let videoAssetId = asset.id;
	let originalDataPath = asset.originalPath;
	let originalFileName = asset.originalFileName;
	let fileCreatedAt = asset.fileCreatedAt;
	let fileModifiedAt = asset.fileModifiedAt;

	if (isLivePhotoStill) {
		videoAssetId = asset.livePhotoVideoId;
		const videoAsset = await immichGet(`/api/assets/${videoAssetId}`);
		originalDataPath = await resolveOriginalDataPath(videoAssetId);
		originalFileName = videoAsset.originalFileName;
		fileCreatedAt = videoAsset.fileCreatedAt;
		fileModifiedAt = videoAsset.fileModifiedAt;
	}

	const hostPath = originalDataPath.replace(/^\/data\//, `${LIBRARY_DIR}/`);
	const fps = await probeFps(hostPath);
	if (fps <= MAX_ACCEPTABLE_FPS) {
		console.log(`[process] ${videoAssetId} already ${fps.toFixed(2)}fps - no normalization needed`);
		recordConfirmedSafe(videoAssetId, hostPath);
		return;
	}

	console.log(`[process] ${videoAssetId} is ${fps.toFixed(2)}fps - normalizing to 30fps`);
	const normalizedPath = path.join(TMP_DIR, `${videoAssetId}-normalized.mp4`);
	await runFfmpeg(hostPath, normalizedPath);

	// Upload BEFORE delete, and only delete once the upload is confirmed
	// - a failure at any point
	// here leaves either the original intact or (rarely) a harmless
	// duplicate, never a lost video.
	const newAssetId = await uploadReplacement(normalizedPath, { originalFileName, fileCreatedAt, fileModifiedAt });
	await deleteAsset(videoAssetId);
	fs.unlinkSync(normalizedPath);

	// Re-establish Live Photo pairing: a plain re-encode + upload strips
	// the embedded Apple Live Photo linking metadata, and there's no way
	// to declare a
	// pairing at upload time for a video meant to attach to an
	// *existing* still (AssetMediaCreateDto's livePhotoVideoId direction
	// only works the other way - uploading a still that references an
	// already-uploaded video). The fix is a separate call: PATCH
	// /api/assets/{stillId} with the new video's id in the body -
	// confirmed live to correctly restore the pairing.
	if (isLivePhotoStill) {
		await setLivePhotoPairing(asset.id, newAssetId);
		console.log(`[process] ${videoAssetId} replaced with ${newAssetId} - re-paired as ${asset.id}'s Live Photo clip`);
	}

	await triggerVideoConversion();
	console.log(`[process] ${videoAssetId} replaced with ${newAssetId} - re-transcode triggered`);
}

// Resolves the real on-disk path for an asset's original upload,
// read-only, without an HTTP download - Postgres isn't reachable over a
// published port, so this shells out to `docker exec` instead; unlike
// `encoded-video/`, `upload/` files are stored under a
// separate random filename only recorded in this column. Only needed
// for the Live Photo fallback path - the common case gets this for
// free from the webhook payload itself.
function resolveOriginalDataPath(assetId) {
	return new Promise((resolve, reject) => {
		if (!UUID_RE.test(assetId)) return reject(new Error("invalid asset id"));
		execFile(
			"docker",
			["exec", "immich_postgres", "psql", "-U", "postgres", "-d", "immich", "-t", "-c",
				`SELECT "originalPath" FROM asset WHERE id = '${assetId}';`],
			(error, stdout) => {
				if (error) return reject(error);
				const dataPath = stdout.trim();
				if (!dataPath) return reject(new Error(`no originalPath found for ${assetId}`));
				resolve(dataPath);
			}
		);
	});
}

// `csv=p=0` leaves a trailing
// comma (e.g. "14400000/246637,\n") that .trim() doesn't strip (a comma
// isn't whitespace) - Number("246637,") is NaN, which is falsy, so the
// old code silently fell through to the bare numerator alone (14.4M
// "fps"). Every real asset happened to still pass MAX_ACCEPTABLE_FPS's
// check correctly by accident (a huge numerator is still > 30.5), so
// this never produced a wrong *decision*, just wrong logged numbers -
// but fixed properly via a regex that only takes the leading digits.
function probeFps(filePath) {
	return new Promise((resolve, reject) => {
		execFile(
			"ffprobe",
			["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=avg_frame_rate", "-of", "csv=p=0", filePath],
			(error, stdout) => {
				if (error) return reject(error);
				const match = stdout.trim().match(/^(\d+)(?:\/(\d+))?/);
				if (!match) return reject(new Error(`unexpected ffprobe output: ${JSON.stringify(stdout)}`));
				const num = Number(match[1]);
				const den = match[2] ? Number(match[2]) : 1;
				resolve(num / den);
			}
		);
	});
}

// An unthrottled ffmpeg encode on a small single-board computer can
// starve Immich's own API long enough to time out the frame's sync (a
// SocketTimeoutException on port 2283, nothing to do with the gate) -
// the same host also has to keep Immich/Postgres responsive while a
// background normalization runs. Two independent throttles, not one:
// `nice` deprioritizes ffmpeg's *scheduling* (yields the CPU to anything
// else that wants it, but would still take 100% of an otherwise-idle
// system), and `-threads` caps how many cores libx264 can ever use *at
// all* regardless of contention. Tuned for a 4-core host (leaves one
// core permanently free) - adjust FFMPEG_MAX_THREADS down if running on
// something smaller. Together, a reasonable stand-in for "never take
// more than ~90% of the system," since an exact percentage cap would
// need installing `cpulimit` separately for a hard guarantee.
const FFMPEG_NICE_LEVEL = "15";
const FFMPEG_MAX_THREADS = "3";

function runFfmpeg(inputPath, outputPath) {
	return new Promise((resolve, reject) => {
		execFile(
			"nice",
			["-n", FFMPEG_NICE_LEVEL, "ffmpeg", "-y", "-i", inputPath, "-threads", FFMPEG_MAX_THREADS,
				"-r", "30", "-c:v", "libx264", "-crf", "18", "-preset", "veryfast", "-c:a", "copy", outputPath],
			(error) => (error ? reject(error) : resolve())
		);
	});
}

async function immichGet(urlPath) {
	const response = await fetch(`${IMMICH_BASE_URL}${urlPath}`, { headers: { "x-api-key": API_KEY } });
	if (!response.ok) throw new Error(`GET ${urlPath} failed: ${response.status}`);
	return response.json();
}

async function uploadReplacement(filePath, originalMeta) {
	const now = new Date().toISOString();
	const form = new FormData();
	form.append("fileCreatedAt", originalMeta.fileCreatedAt || now);
	form.append("fileModifiedAt", originalMeta.fileModifiedAt || now);
	form.append("filename", originalMeta.originalFileName || path.basename(filePath));
	form.append("assetData", new Blob([fs.readFileSync(filePath)]), path.basename(filePath));

	const response = await fetch(`${IMMICH_BASE_URL}/api/assets`, {
		method: "POST",
		headers: { "x-api-key": API_KEY },
		body: form,
	});
	if (!response.ok) throw new Error(`upload failed: ${response.status} ${await response.text()}`);
	const json = await response.json();
	return json.id;
}

// PATCH /api/assets/:id accepts
// livePhotoVideoId in its body (UpdateAssetDto) - this is the only way
// to (re-)establish a Live Photo pairing after the fact; the upload-time
// field only works in the opposite direction (a still referencing an
// already-uploaded video, not attaching a video to an existing still).
async function setLivePhotoPairing(stillAssetId, videoAssetId) {
	const response = await fetch(`${IMMICH_BASE_URL}/api/assets/${stillAssetId}`, {
		method: "PATCH",
		headers: { "x-api-key": API_KEY, "Content-Type": "application/json" },
		body: JSON.stringify({ livePhotoVideoId: videoAssetId }),
	});
	if (!response.ok) throw new Error(`live photo re-pairing failed: ${response.status} ${await response.text()}`);
}

async function deleteAsset(assetId) {
	const response = await fetch(`${IMMICH_BASE_URL}/api/assets`, {
		method: "DELETE",
		headers: { "x-api-key": API_KEY, "Content-Type": "application/json" },
		body: JSON.stringify({ ids: [assetId], force: true }),
	});
	if (!response.ok) throw new Error(`delete failed: ${response.status} ${await response.text()}`);
}

async function triggerVideoConversion() {
	const response = await fetch(`${IMMICH_BASE_URL}/api/jobs/videoConversion`, {
		method: "PUT",
		headers: { "x-api-key": API_KEY, "Content-Type": "application/json" },
		body: JSON.stringify({ command: "start", force: true }),
	});
	if (!response.ok) throw new Error(`job trigger failed: ${response.status}`);
}

// --- C. Webcam clip capture --------------------------------------------
//
// Mixes short clips from public live webcams into the frame's own
// slideshow. Deliberately `-c:v copy` (stream-copy, no
// re-encode) - this workload is network-bound, not
// CPU-bound (near-0% CPU, flat loadavg), unlike section B's real
// libx264 encode above, which is why this needs none of that section's
// nice/-threads throttling. Audio is dropped entirely (`-an`) - some
// cameras carry live sound, which has no use on a silent picture frame
// and would otherwise play through the frame's speaker. Cropping to the
// frame's portrait aspect has a real, measurable multi-core CPU cost, so
// it's deliberately deferred - ships uncropped for now.

const WEBCAM_DIR = path.join(__dirname, "webcam-cache");
fs.mkdirSync(WEBCAM_DIR, { recursive: true });

const WEBCAM_CAPTURE_INTERVAL_MS = 30 * 60 * 1_000;
const WEBCAM_CLIP_DURATION_S = 30;
// A camera down this long stops being served at all (via the manifest
// below) rather than looping an increasingly-stale frozen clip forever.
const WEBCAM_MAX_STALE_MS = 3 * 60 * 60 * 1_000;
// Hard per-camera kill so one genuinely hung stream can never block the
// rest of a cycle's cameras or delay the next 30-min tick (Node's own
// execFile timeout, not just ffmpeg's -reconnect flags below, which only
// cover transient reconnects, not a fully-stuck connection).
const WEBCAM_CAPTURE_TIMEOUT_MS = 60_000;

const WEBCAM_STATE_PATH = path.join(__dirname, "webcam-state.json");

// Master on/off switch for the whole feature (an admin panel's
// /frameo/webcam toggle). Deliberately the single source of truth this
// whole feature checks - the scheduled AND forced capture cycles, and the manifest/clip
// endpoints the frame reads - all gate on this one flag, rather than each
// having their own on/off state, so "disabled" actually means nothing
// webcam-related runs anywhere, not just "the Pi stops capturing but old
// clips still serve" or some other partial state. Defaults to enabled
// (`true`) so an upgrade with no webcam-enabled.json yet preserves today's
// live behavior rather than silently going dark.
const WEBCAM_ENABLED_PATH = path.join(__dirname, "webcam-enabled.json");

function isWebcamFeatureEnabled() {
	try {
		return JSON.parse(fs.readFileSync(WEBCAM_ENABLED_PATH, "utf8")).enabled !== false;
	} catch {
		return true;
	}
}

function setWebcamFeatureEnabled(enabled) {
	fs.writeFileSync(WEBCAM_ENABLED_PATH, JSON.stringify({ enabled }));
}

function loadWebcamState() {
	try {
		return JSON.parse(fs.readFileSync(WEBCAM_STATE_PATH, "utf8"));
	} catch {
		return {};
	}
}

// Each entry is `{at, durationSec}` - durationSec lets /webcam/status
// show each clip's actual captured length, not just assume it's always
// the requested WEBCAM_CLIP_DURATION_S (a stream that dropped
// mid-capture would produce a shorter file, which this surfaces rather
// than hides). Tolerates an older flat-timestamp format (`state[camId]`
// as a bare number) via lastSuccessEntry()
// below, so an in-place upgrade doesn't need a migration step - stale
// entries just show no duration until their next capture overwrites them.
function recordWebcamSuccess(camId, durationSec) {
	const state = loadWebcamState();
	state[camId] = { at: Date.now(), durationSec };
	fs.writeFileSync(WEBCAM_STATE_PATH, JSON.stringify(state));
}

function lastSuccessEntry(state, camId) {
	const entry = state[camId];
	if (entry == null) return null;
	// Legacy shape: a bare timestamp number.
	if (typeof entry === "number") return { at: entry, durationSec: null };
	return entry;
}

function probeDurationSeconds(filePath) {
	return new Promise((resolve, reject) => {
		execFile(
			"ffprobe",
			["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", filePath],
			(error, stdout) => {
				if (error) return reject(error);
				const value = Number(stdout.trim());
				if (!Number.isFinite(value)) return reject(new Error(`unexpected ffprobe output: ${JSON.stringify(stdout)}`));
				resolve(value);
			}
		);
	});
}

// Reads the frame's LocalControlServer /status
// (MainActivity.buildStatusJson()'s screenAwake field), read-only,
// never posts. Fails OPEN (assume "not asleep") on any error, including
// frameControlUrl not being configured at all - a transient
// control-server hiccup, or the feature simply not being set up, must
// never silently stop clip refreshing.
//
// Checks the frame's real screen-power state directly, not the
// night-mode *schedule* window (GET /action/night-mode's
// sleepTime/wakeTime, a plain range check against the current clock
// time). A schedule-only check misses anything that puts the screen to
// sleep outside the configured window - a presence-based
// home-automation trigger, a manual sleep-now action - and would keep
// capturing webcam clips into a screen that wasn't going to show them
// until it woke back up. Real screenAwake is the actual signal this
// function has always wanted ("is anyone going to see this clip
// soon"), regardless of *why* the screen is off.
async function isFrameAsleep() {
	if (!FRAME_CONTROL_URL) return false;
	try {
		const response = await fetch(`${FRAME_CONTROL_URL}/status`);
		if (!response.ok) return false;
		const status = await response.json();
		return status.screenAwake === false;
	} catch (error) {
		console.warn(`[webcam] could not reach frame's status - assuming awake: ${error.message}`);
		return false;
	}
}

function captureWebcam(streamUrl, destPath) {
	return new Promise((resolve, reject) => {
		execFile(
			"ffmpeg",
			[
				"-y",
				"-reconnect", "1",
				"-reconnect_streamed", "1",
				"-reconnect_delay_max", "2",
				"-i", streamUrl,
				"-t", String(WEBCAM_CLIP_DURATION_S),
				"-c:v", "copy",
				"-an",
				destPath,
			],
			{ timeout: WEBCAM_CAPTURE_TIMEOUT_MS, killSignal: "SIGKILL" },
			(error) => (error ? reject(error) : resolve())
		);
	});
}

async function captureOneWebcam(cam) {
	const tmpPath = path.join(WEBCAM_DIR, `${cam.id}.tmp.mp4`);
	const finalPath = path.join(WEBCAM_DIR, `${cam.id}.mp4`);
	try {
		await captureWebcam(cam.streamUrl, tmpPath);
		// Probed before the rename (still at tmpPath) - a probe failure here
		// is unexpected for a file ffmpeg itself just finished writing
		// successfully, but if it happens, still commit the clip with a
		// null duration rather than discarding an otherwise-good capture.
		let durationSec = null;
		try {
			durationSec = await probeDurationSeconds(tmpPath);
		} catch (error) {
			console.warn(`[webcam] ${cam.id} captured but duration probe failed: ${error.message}`);
		}
		fs.renameSync(tmpPath, finalPath);
		recordWebcamSuccess(cam.id, durationSec);
		console.log(`[webcam] ${cam.id} refreshed${durationSec ? ` (${durationSec.toFixed(1)}s)` : ""}`);
	} catch (error) {
		console.warn(`[webcam] ${cam.id} grab failed - keeping previous clip if any: ${error.message}`);
		try { fs.unlinkSync(tmpPath); } catch {}
	}
}

// Guards against a forced refresh (POST /webcam/refresh, below) overlapping
// with the scheduled interval's own cycle - two concurrent passes would
// both be writing/renaming the same per-camera files.
let webcamCycleInProgress = false;

// Independently try/caught per camera (see captureOneWebcam()) - one
// camera being down must never stop the others from refreshing.
// `force` (the admin panel's "Force update" button) deliberately
// bypasses the night-mode skip below - an explicit manual action means
// the caller already knows what they're asking for, same reasoning as the
// frame's own refresh-cache/reset-faces actions being disruptive-on-purpose.
async function captureWebcamCycle(force = false) {
	if (WEBCAMS.length === 0) return;
	if (!isWebcamFeatureEnabled()) {
		console.log("[webcam] feature disabled - skipping capture cycle entirely");
		return;
	}
	if (webcamCycleInProgress) {
		console.log("[webcam] a cycle is already running - ignoring this trigger");
		return;
	}
	if (!force && (await isFrameAsleep())) {
		console.log("[webcam] frame is asleep - skipping this cycle entirely");
		return;
	}
	webcamCycleInProgress = true;
	try {
		// Sequential, not parallel - a handful of 30s captures adds up to a
		// small fraction of the WEBCAM_CAPTURE_INTERVAL_MS period even run
		// one at a time, and sequential naturally staggers bandwidth/socket
		// load instead of bursting every camera at once.
		for (const cam of WEBCAMS) {
			await captureOneWebcam(cam);
		}
	} finally {
		webcamCycleInProgress = false;
	}
}

function clipIsAvailable(cam, state, now) {
	const entry = lastSuccessEntry(state, cam.id);
	if (!entry || now - entry.at > WEBCAM_MAX_STALE_MS) return false;
	return fs.existsSync(path.join(WEBCAM_DIR, `${cam.id}.mp4`));
}

// Empty manifest when disabled - regardless of what's still sitting in
// webcam-cache/ from before - is the one mechanism that removes webcam
// clips from the frame's shuffled rotation without any frame-side special
// case: WebcamClipSync.sync() (the frame) already treats "manifest says no
// clips" as "nothing to add this round", the exact same path it already
// takes for a Pi that's simply unreachable. See WEBCAM_ENABLED_PATH's own
// comment for why this one flag is the single gate the whole feature uses.
app.get("/webcam/clips", (req, res) => {
	if (!isWebcamFeatureEnabled()) return res.json({ clips: [] });
	const state = loadWebcamState();
	const now = Date.now();
	const clips = WEBCAMS.filter((cam) => clipIsAvailable(cam, state, now)).map((cam) => cam.id);
	res.json({ clips });
});

app.get("/webcam/clip/:id", (req, res) => {
	if (!isWebcamFeatureEnabled()) return res.status(404).end();
	const id = req.params.id;
	if (!WEBCAM_ID_RE.test(id) || !WEBCAMS.some((cam) => cam.id === id)) return res.status(400).end();

	const clipPath = path.join(WEBCAM_DIR, `${id}.mp4`);
	if (!fs.existsSync(clipPath)) return res.status(404).end();

	res.setHeader("Content-Type", "video/mp4");
	fs.createReadStream(clipPath).pipe(res);
});

// Admin-panel stats (an admin panel's /frameo/webcam page) - per-camera
// label/last-update/availability, plus whether a cycle is running right
// now. Local-only (not proxied over the internet), so no auth needed
// beyond whatever LAN-only access control the admin panel itself uses.
app.get("/webcam/status", (req, res) => {
	const state = loadWebcamState();
	const now = Date.now();
	res.json({
		enabled: isWebcamFeatureEnabled(),
		cycleInProgress: webcamCycleInProgress,
		cameras: WEBCAMS.map((cam) => {
			const entry = lastSuccessEntry(state, cam.id);
			return {
				id: cam.id,
				label: cam.label,
				lastSuccessAt: entry ? entry.at : null,
				ageMs: entry ? now - entry.at : null,
				durationSec: entry ? entry.durationSec : null,
				available: clipIsAvailable(cam, state, now),
			};
		}),
	});
});

// Admin panel's "Force update" button - triggers an immediate
// capture cycle instead of waiting for the next 30-min tick, bypassing
// the night-mode skip (see captureWebcamCycle()'s own comment on `force`).
// Fire-and-forget: a full cycle can take a minute or more with several
// cameras, so this acks immediately rather than making the caller's HTTP client block.
app.post("/webcam/refresh", (req, res) => {
	// A forced refresh while the feature is disabled must actually do
	// nothing, not just get skipped silently deep inside captureWebcamCycle()
	// (which would still happen on its own via the isWebcamFeatureEnabled()
	// check in there) - checked here too so the caller gets an honest
	// "disabled" status back instead of the misleading "started".
	if (!isWebcamFeatureEnabled()) {
		res.json({ status: "disabled" });
		return;
	}
	if (webcamCycleInProgress) {
		res.json({ status: "already-running" });
		return;
	}
	res.json({ status: "started" });
	captureWebcamCycle(true).catch((error) => console.error(`[webcam] forced capture cycle failed: ${error.message}`));
});

// Master on/off switch (an admin panel's /frameo/webcam toggle) - see
// WEBCAM_ENABLED_PATH's own comment for why this one flag
// is the single gate the whole feature checks.
app.get("/webcam/enabled", (req, res) => {
	res.json({ enabled: isWebcamFeatureEnabled() });
});

app.post("/webcam/enabled", (req, res) => {
	const enabled = req.body.enabled === true;
	setWebcamFeatureEnabled(enabled);
	res.json({ enabled });
});

app.listen(PORT, "0.0.0.0", () => {
	console.log(`pi-video-gate listening on :${PORT}`);
	if (WEBCAMS.length > 0) {
		captureWebcamCycle().catch((error) => console.error(`[webcam] initial capture cycle failed: ${error.message}`));
		setInterval(() => {
			captureWebcamCycle().catch((error) => console.error(`[webcam] capture cycle failed: ${error.message}`));
		}, WEBCAM_CAPTURE_INTERVAL_MS);
	}
});
