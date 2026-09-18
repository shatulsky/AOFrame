/* CRUD photo/video storage - the backend for Immich-optional (local)
 * mode, see the app's `photoSource: local` config. Deliberately plain
 * disk storage plus a single JSON manifest (id -> metadata), not a
 * database - this is a reference implementation sized for a personal
 * photo frame's library, not a multi-user service.
 *
 * Photo bytes live under `photoStorageDir` (config.json) keyed by a
 * generated id, alongside `manifest.json` tracking each id's original
 * filename/extension/upload time. The app's own local-mode client polls
 * GET /api/photos for the current list and fetches each one by id - see
 * the setup guide's "Immich-optional mode" section for how the two sides
 * talk to each other.
 */
const express = require("express");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const multer = require("multer");
const { execFile } = require("child_process");
const config = require("../config.json");

const STORAGE_DIR = path.resolve(__dirname, "..", config.photoStorageDir || "./photos");
const MANIFEST_PATH = path.join(STORAGE_DIR, "manifest.json");
fs.mkdirSync(STORAGE_DIR, { recursive: true });

function loadManifest() {
	try {
		return JSON.parse(fs.readFileSync(MANIFEST_PATH, "utf8"));
	} catch (error) {
		return {};
	}
}

function saveManifest(manifest) {
	fs.writeFileSync(MANIFEST_PATH, JSON.stringify(manifest, null, 2));
}

// Every uploaded video gets forced through this, unconditionally - unlike
// pi-video-gate's Immich-side normalization (fps only, since Immich's own
// uploads are rarely more exotic than that), this server has no idea what
// a user will throw at it. A phone's HDR "Cinematic"/10-bit export (H.264
// High 10 or HEVC Main10, BT.2020/HLG color) decodes its audio track fine
// on both the emulator's software decoder and the frame's Rockchip
// hardware decoder, but produces zero visible video frames on either -
// confirmed live, not a theoretical concern. Same ffmpeg shape as
// pi-video-gate's own runFfmpeg() (-r 30, libx264, no explicit -pix_fmt) -
// that command already re-encodes 10-bit HDR sources down to plain 8-bit
// on the Immich path and is confirmed working on this hardware, so this
// mirrors it rather than inventing a separate color-managed tonemap chain.
function transcodeVideo(inputPath, outputPath) {
	return new Promise((resolve, reject) => {
		execFile(
			"ffmpeg",
			["-y", "-i", inputPath, "-r", "30", "-c:v", "libx264", "-crf", "18", "-preset", "veryfast", "-c:a", "copy", outputPath],
			(error) => (error ? reject(error) : resolve())
		);
	});
}

const upload = multer({
	storage: multer.diskStorage({
		destination: STORAGE_DIR,
		filename: (req, file, cb) => {
			const id = crypto.randomUUID();
			req.uploadedId = id;
			cb(null, id + path.extname(file.originalname));
		}
	}),
	// 500MB ceiling - generous enough for an unedited phone/camera video
	// clip before transcodeVideo() below normalizes it down, while still
	// bounding a single upload's disk/memory footprint.
	limits: { fileSize: 500 * 1024 * 1024 },
	fileFilter: (req, file, cb) => {
		cb(null, /^(image\/(jpeg|png|webp|gif)|video\/mp4)$/.test(file.mimetype));
	}
});

module.exports = function registerPhotoRoutes(app) {
	app.post("/api/photos", upload.single("photo"), async (req, res) => {
		if (!req.file) {
			res.status(400).json({ error: "no file uploaded (expected multipart field 'photo', one of image/jpeg|png|webp|gif|video/mp4)" });
			return;
		}

		if (req.file.mimetype === "video/mp4") {
			const uploadedPath = path.join(STORAGE_DIR, req.file.filename);
			const normalizedPath = path.join(STORAGE_DIR, `${req.uploadedId}.normalized.mp4`);
			try {
				await transcodeVideo(uploadedPath, normalizedPath);
				fs.renameSync(normalizedPath, path.join(STORAGE_DIR, `${req.uploadedId}.mp4`));
				if (uploadedPath !== path.join(STORAGE_DIR, `${req.uploadedId}.mp4`)) fs.rmSync(uploadedPath, { force: true });
			} catch (error) {
				fs.rmSync(uploadedPath, { force: true });
				fs.rmSync(normalizedPath, { force: true });
				res.status(400).json({ error: `video could not be processed: ${error.message}` });
				return;
			}
		}

		const manifest = loadManifest();
		manifest[req.uploadedId] = {
			originalName: req.file.originalname,
			// Always .mp4 for video - transcodeVideo() above always
			// outputs mp4 regardless of the uploaded container.
			ext: req.file.mimetype === "video/mp4" ? ".mp4" : path.extname(req.file.originalname),
			mimeType: req.file.mimetype,
			uploadedAt: new Date().toISOString()
		};
		saveManifest(manifest);
		res.status(201).json({ id: req.uploadedId, ...manifest[req.uploadedId] });
	});

	app.get("/api/photos", (req, res) => {
		const manifest = loadManifest();
		res.json(Object.entries(manifest).map(([id, meta]) => ({ id, ...meta })));
	});

	app.get("/api/photos/:id", (req, res) => {
		const manifest = loadManifest();
		const meta = manifest[req.params.id];
		if (!meta) {
			res.status(404).end();
			return;
		}
		res.sendFile(path.join(STORAGE_DIR, req.params.id + meta.ext));
	});

	// Manual Ken Burns face target for local-mode photos - local mode has
	// no face-detection story of its own (see LocalAssetSync.kt's own
	// comment), so this is the only way to give an IMAGE asset a faceX/
	// faceY target at all. Values are the same 0-100 top-left-origin
	// percentages ImmichAsset.faceX/faceY already expect.
	app.patch("/api/photos/:id/face", (req, res) => {
		const manifest = loadManifest();
		const meta = manifest[req.params.id];
		if (!meta) {
			res.status(404).end();
			return;
		}
		const { faceX, faceY } = req.body || {};
		if (typeof faceX !== "number" || typeof faceY !== "number" || faceX < 0 || faceX > 100 || faceY < 0 || faceY > 100) {
			res.status(400).json({ error: "expected JSON body {faceX, faceY} as numbers 0-100" });
			return;
		}
		meta.faceX = faceX;
		meta.faceY = faceY;
		saveManifest(manifest);
		res.json({ id: req.params.id, ...meta });
	});

	app.delete("/api/photos/:id", (req, res) => {
		const manifest = loadManifest();
		const meta = manifest[req.params.id];
		if (!meta) {
			res.status(404).end();
			return;
		}
		fs.rmSync(path.join(STORAGE_DIR, req.params.id + meta.ext), { force: true });
		delete manifest[req.params.id];
		saveManifest(manifest);
		res.status(204).end();
	});
};
