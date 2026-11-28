/* Test fixtures for the reference server: a fake frame control server
 * (records every request, answers from a script) and an in-process app on a
 * free port with a temp photo folder. Each helper returns a close() for cleanup.
 */
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { createApp } = require("../server");

function listen(server) {
	return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve(server.address().port)));
}

// startFakeFrame(answer): answer(req, body) -> { status, body } (body is a string
// or an object sent as JSON). requests holds { method, url, body } of each call.
async function startFakeFrame(answer = () => ({ status: 200, body: {} })) {
	const requests = [];
	const server = http.createServer((req, res) => {
		let raw = "";
		req.on("data", (c) => { raw += c; });
		req.on("end", () => {
			requests.push({ method: req.method, url: req.url, body: raw, contentType: req.headers["content-type"] });
			const { status = 200, body = {} } = answer(req, raw) || {};
			res.statusCode = status;
			res.end(typeof body === "string" ? body : JSON.stringify(body));
		});
	});
	const port = await listen(server);
	return { url: `http://127.0.0.1:${port}`, requests, close: () => new Promise((r) => server.close(r)) };
}

// startApp(config, deps): the real createApp() on a free port, with a temp
// photoStorageDir unless config gives one.
async function startApp(config = {}, deps = {}) {
	const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aoframe-server-"));
	const app = createApp({ photoStorageDir: dir, ...config }, deps);
	const server = http.createServer(app);
	const port = await listen(server);
	return {
		url: `http://127.0.0.1:${port}`,
		dir,
		close: async () => {
			await new Promise((r) => server.close(r));
			fs.rmSync(dir, { recursive: true, force: true });
		}
	};
}

module.exports = { startFakeFrame, startApp };
