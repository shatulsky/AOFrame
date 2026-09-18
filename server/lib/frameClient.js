/* Thin HTTP client for the app's own on-device LocalControlServer
 * (NanoHTTPD, port 8099 by default) - every route in routes/frame-actions.js
 * is a pass-through proxy onto this. The device owns the actual config/state;
 * this server (and anything talking to it) is just a remote-editing/dashboard
 * convenience, never a second source of truth.
 */
const http = require("http");
const config = require("../config.json");

const FRAME_CONTROL_URL = config.frameControlUrl || "http://127.0.0.1:8099";

function frameRequest(path, method, body) {
	return new Promise((resolve, reject) => {
		const data = body !== undefined ? JSON.stringify(body) : null;
		const req = http.request(`${FRAME_CONTROL_URL}${path}`, {
			method,
			headers: data ? { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(data) } : {}
		}, (res) => {
			let raw = "";
			res.on("data", (chunk) => { raw += chunk; });
			res.on("end", () => {
				if (res.statusCode >= 400) {
					reject(new Error(`Frame returned ${res.statusCode}: ${raw}`));
					return;
				}
				try {
					resolve(raw ? JSON.parse(raw) : {});
				} catch (error) {
					reject(new Error(`Frame returned invalid JSON: ${raw}`));
				}
			});
		});
		req.on("error", reject);
		if (data) req.write(data);
		req.end();
	});
}

module.exports = { frameRequest, FRAME_CONTROL_URL };
