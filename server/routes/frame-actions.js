/* Proxy routes onto the device's own LocalControlServer - see
 * lib/frameClient.js and docs/local-control-server-api.md for the full
 * request/response shapes this mirrors 1:1. Every route here just
 * forwards method + JSON body to the same path on the device and relays
 * whatever it returns; the device is the source of truth, this is a
 * thin remote-editing/dashboard convenience on top.
 */
const express = require("express");
const { frameRequest } = require("../lib/frameClient");

module.exports = function registerFrameActionRoutes(app) {
	const json = express.json();

	// Config-style actions: GET returns current config, POST saves + echoes it back.
	for (const path of ["/action/night-mode", "/action/countdown", "/action/slideshow-settings", "/action/webcam-test-mode"]) {
		app.get(path, async (req, res) => {
			try {
				res.json(await frameRequest(path, "GET"));
			} catch (error) {
				res.status(502).json({ error: error.message });
			}
		});
		app.post(path, json, async (req, res) => {
			try {
				res.json(await frameRequest(path, "POST", req.body));
			} catch (error) {
				res.status(502).json({ error: error.message });
			}
		});
	}

	// Fire-and-forget actions: POST only, no body.
	for (const path of ["/action/refresh-cache", "/action/reset-faces", "/action/reshuffle", "/action/immich-webhook", "/action/refresh-webcam"]) {
		app.post(path, async (req, res) => {
			try {
				res.json(await frameRequest(path, "POST"));
			} catch (error) {
				res.status(502).json({ error: error.message });
			}
		});
	}

	// Read-only actions: GET only.
	for (const path of ["/action/webcam-sync-status", "/status"]) {
		app.get(path, async (req, res) => {
			try {
				res.json(await frameRequest(path, "GET"));
			} catch (error) {
				res.status(502).json({ error: error.message });
			}
		});
	}
};
