/* Test fixture for server.js: createGate() with a fake execFile (no ffmpeg,
 * ffprobe, nice or docker runs), a fake fetch (no Immich, no frame), a temp
 * state folder, a fake clock and no retry delay. Not deployed (deploy.sh
 * ships an explicit file list).
 */
const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const { createGate } = require("./server");

const ASSET = "0a1b2c3d-1111-4222-8333-444455556666";
const STILL = "0a1b2c3d-aaaa-4bbb-8ccc-ddddeeeeffff";
const NEW_ASSET = "9f9f9f9f-1111-4222-8333-444455556666";

// fakeExec(script): script[cmd](args) -> { stdout } | { error } | undefined.
// An ffmpeg run (directly or under nice) writes its output file unless the
// script returns an error. calls holds [cmd, args] of every run.
function fakeExec(script = {}) {
	const calls = [];
	const fn = (cmd, args, opts, cb) => {
		if (typeof opts === "function") cb = opts;
		calls.push([cmd, args]);
		const tool = cmd === "nice" ? args[2] : cmd;
		const answer = (script[tool] && script[tool](args, calls)) || {};
		if (answer.error) return setImmediate(() => cb(answer.error, "", ""));
		if (tool === "ffmpeg" && !answer.noOutput) fs.writeFileSync(args[args.length - 1], answer.content || "clip");
		setImmediate(() => cb(null, answer.stdout || "", ""));
	};
	return { fn, calls };
}

// fakeFetch(routes): routes["METHOD /path"](init) -> { status, body } (body is
// an object sent as JSON or a string). Unknown routes answer 500. calls holds
// { method, path, init } of every request.
function fakeFetch(routes = {}) {
	const calls = [];
	const fn = async (url, init = {}) => {
		const method = init.method || "GET";
		const { pathname } = new URL(url);
		calls.push({ method, path: pathname, init });
		const handler = routes[`${method} ${pathname}`];
		if (!handler) return new Response("no route", { status: 500 });
		const { status = 200, body = {}, throws } = (await handler(init)) || {};
		if (throws) throw throws;
		return new Response(typeof body === "string" ? body : JSON.stringify(body), { status });
	};
	return { fn, calls };
}

// makeGate({ config, exec, fetch, now }): a gate on a temp state folder with
// a temp library (one library id folder). Returns the gate plus helpers.
function makeGate(t, { config = {}, exec = fakeExec(), fetch = fakeFetch(), now } = {}) {
	const root = fs.mkdtempSync(path.join(os.tmpdir(), "pi-video-gate-"));
	const libraryDir = path.join(root, "library");
	fs.mkdirSync(path.join(libraryDir, "encoded-video", "lib-1"), { recursive: true });
	const stateDir = path.join(root, "state");
	fs.mkdirSync(stateDir);
	const clock = { ms: now ?? 1_000_000_000_000 };
	const sleeps = [];
	const gate = createGate(
		{ immichBaseUrl: "http://immich.test", apiKey: "test-key", libraryDir, ...config },
		{ execFile: exec.fn, fetch: fetch.fn, stateDir, now: () => clock.ms, sleep: async (ms) => { sleeps.push(ms); } }
	);
	t.after(() => fs.rmSync(root, { recursive: true, force: true }));
	return { gate, exec, fetch, root, libraryDir, stateDir, clock, sleeps };
}

// listen(t, app): the app on a free loopback port; returns its base URL.
async function listen(t, app) {
	const server = http.createServer(app);
	await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
	t.after(() => new Promise((resolve) => server.close(resolve)));
	return `http://127.0.0.1:${server.address().port}`;
}

// quiet(t): silence and record console output of the code under test.
function quiet(t) {
	const lines = { log: [], warn: [], error: [] };
	for (const level of Object.keys(lines)) {
		t.mock.method(console, level, (...args) => lines[level].push(args.join(" ")));
	}
	return lines;
}

module.exports = { ASSET, STILL, NEW_ASSET, fakeExec, fakeFetch, makeGate, listen, quiet };
