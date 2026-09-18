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
	// clip (this app's LocalAssetSync has no transcoding step of its own,
	// unlike pi-video-gate's Immich-side fps normalization), while still
	// bounding a single upload's disk/memory footprint.
	limits: { fileSize: 500 * 1024 * 1024 },
	fileFilter: (req, file, cb) => {
		cb(null, /^(image\/(jpeg|png|webp|gif)|video\/mp4)$/.test(file.mimetype));
	}
});

module.exports = function registerPhotoRoutes(app) {
	app.post("/api/photos", upload.single("photo"), (req, res) => {
		if (!req.file) {
			res.status(400).json({ error: "no file uploaded (expected multipart field 'photo', one of image/jpeg|png|webp|gif|video/mp4)" });
			return;
		}
		const manifest = loadManifest();
		manifest[req.uploadedId] = {
			originalName: req.file.originalname,
			ext: path.extname(req.file.originalname),
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
