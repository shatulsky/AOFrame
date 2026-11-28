/* Section C (webcam clip capture) of server.js, in-process with fakes (see
 * gate-fixture.js). No real stream is opened and no ffmpeg runs.
 */
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const { fakeExec, fakeFetch, makeGate, listen, quiet } = require("./gate-fixture");

const CAMS = [
	{ id: "harbor", streamUrl: "https://cam.example/h.m3u8?token=t", label: "Harbor" },
	{ id: "square", streamUrl: "https://cam.example/s.m3u8", label: "Square" },
];
const HOUR = 60 * 60 * 1000;

// Each capture writes different bytes, so no clip looks frozen; duration 29.5 s.
function cameras({ fail = [], content } = {}) {
	let n = 0;
	return fakeExec({
		ffmpeg: (args) => {
			const url = args[args.indexOf("-i") + 1];
			if (fail.some((id) => url.includes(`/${id[0]}.m3u8`))) return { error: new Error("Server returned 404\nConnection reset") };
			return { content: content ?? `clip-${++n}` };
		},
		ffprobe: () => ({ stdout: "29.5\n" }),
	});
}

function gateWith(t, opts = {}) {
	return makeGate(t, { config: { webcams: CAMS, frameControlUrl: opts.frameUrl, ...opts.config }, exec: opts.exec || cameras(), fetch: opts.fetch, now: opts.now });
}

const post = (url, route, body) => fetch(url + route, { method: "POST", headers: { "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
const cache = (stateDir) => fs.readdirSync(path.join(stateDir, "webcam-cache")).sort();

test("a cycle captures each camera in turn and the clips are listed and served", async (t) => {
	quiet(t);
	const g = gateWith(t);
	await g.gate.captureWebcamCycle();
	const grabs = g.exec.calls.filter((c) => c[0] === "ffmpeg").map((c) => c[1]);
	assert.equal(grabs.length, 2);
	assert.ok(grabs[0].includes("-an") && grabs[0].includes("copy"), "stream copy without audio");
	assert.deepEqual(cache(g.stateDir), ["harbor.mp4", "square.mp4"]);
	const url = await listen(t, g.gate.app);
	assert.deepEqual(await (await fetch(`${url}/webcam/clips`)).json(), { clips: ["harbor", "square"] });
	const clip = await fetch(`${url}/webcam/clip/harbor`);
	assert.equal(clip.headers.get("content-type"), "video/mp4");
	assert.equal(await clip.text(), "clip-1");
});

test("the capture of one ffmpeg run is killed after 60 s", async (t) => {
	quiet(t);
	const calls = [];
	const exec = { calls, fn: (cmd, args, opts, cb) => { calls.push([cmd, args, opts]); if (typeof opts === "function") cb = opts; fs.writeFileSync(args.at(-1), "x"); setImmediate(() => cb(null, "30", "")); } };
	const g = gateWith(t, { exec });
	await g.gate.captureWebcamCycle();
	assert.deepEqual(calls.find((c) => c[0] === "ffmpeg")[2], { timeout: 60000, killSignal: "SIGKILL" });
});

test("one dead camera does not stop the others and its old clip stays", async (t) => {
	quiet(t);
	const g = gateWith(t, { exec: cameras({ fail: ["square"] }) });
	fs.mkdirSync(path.join(g.stateDir, "webcam-cache"), { recursive: true });
	fs.writeFileSync(path.join(g.stateDir, "webcam-cache", "square.mp4"), "old");
	await g.gate.captureWebcamCycle();
	assert.deepEqual(cache(g.stateDir), ["harbor.mp4", "square.mp4"]);
	assert.equal(fs.readFileSync(path.join(g.stateDir, "webcam-cache", "square.mp4"), "utf8"), "old");
});

test("failures: a warning each time, the ffmpeg output as ERROR lines only on the third in a row", async (t) => {
	const log = quiet(t);
	const g = gateWith(t, { config: { webcams: [CAMS[1]] }, exec: cameras({ fail: ["square"] }) });
	for (let i = 0; i < 4; i++) await g.gate.captureWebcamCycle();
	assert.equal(log.warn.filter((l) => l.includes("square grab failed")).length, 3);
	assert.deepEqual(log.error.filter((l) => l.includes("ERROR square")).map((l) => l.replace(/^.*ERROR square/, "")), [
		" grab failed 3 cycles in a row - keeping previous clip if any. ffmpeg output:",
		": Server returned 404",
		": Connection reset",
	]);
});

test("a success after failures resets the count and says how many cycles failed", async (t) => {
	const log = quiet(t);
	let fail = true;
	const exec = fakeExec({ ffmpeg: () => (fail ? { error: new Error("x") } : { content: "new" }), ffprobe: () => ({ stdout: "30" }) });
	const g = gateWith(t, { config: { webcams: [CAMS[0]] }, exec });
	await g.gate.captureWebcamCycle();
	await g.gate.captureWebcamCycle();
	fail = false;
	await g.gate.captureWebcamCycle();
	assert.match(log.log.join("\n"), /harbor refreshed \(30\.0s\) after 2 failed cycle\(s\)/);
	fail = true;
	await g.gate.captureWebcamCycle();
	assert.match(log.warn.at(-1), /\(1 in a row\)/);
});

test("a frozen stream (same bytes as the last clip) counts as a failure and keeps the old success time", async (t) => {
	const log = quiet(t);
	const g = gateWith(t, { config: { webcams: [CAMS[0]] }, exec: cameras({ content: "same" }) });
	await g.gate.captureWebcamCycle();
	const first = JSON.parse(fs.readFileSync(path.join(g.stateDir, "webcam-state.json"), "utf8")).harbor.at;
	g.clock.ms += 1000;
	await g.gate.captureWebcamCycle();
	const second = JSON.parse(fs.readFileSync(path.join(g.stateDir, "webcam-state.json"), "utf8")).harbor.at;
	assert.equal(second, first);
	assert.match(log.warn.join("\n"), /stream looks frozen/);
	assert.deepEqual(cache(g.stateDir), ["harbor.mp4"]);
});

test("a duration probe failure still keeps the clip, with no duration", async (t) => {
	const log = quiet(t);
	const exec = fakeExec({ ffprobe: () => ({ error: new Error("bad") }) });
	const g = gateWith(t, { config: { webcams: [CAMS[0]] }, exec });
	await g.gate.captureWebcamCycle();
	assert.deepEqual(cache(g.stateDir), ["harbor.mp4"]);
	const state = JSON.parse(fs.readFileSync(path.join(g.stateDir, "webcam-state.json"), "utf8"));
	assert.equal(state.harbor.durationSec, null);
	assert.match(log.warn.join("\n"), /duration probe failed/);
});

test("a duration that is not a number counts as a probe failure", async (t) => {
	quiet(t);
	const exec = fakeExec({ ffprobe: () => ({ stdout: "N/A" }) });
	const g = gateWith(t, { config: { webcams: [CAMS[0]] }, exec });
	await g.gate.captureWebcamCycle();
	const state = JSON.parse(fs.readFileSync(path.join(g.stateDir, "webcam-state.json"), "utf8"));
	assert.equal(state.harbor.durationSec, null);
});

test("a clip older than 3 hours drops out of the list but the file stays", async (t) => {
	quiet(t);
	const g = gateWith(t);
	await g.gate.captureWebcamCycle();
	const url = await listen(t, g.gate.app);
	g.clock.ms += 3 * HOUR;
	assert.equal((await (await fetch(`${url}/webcam/clips`)).json()).clips.length, 2, "exactly 3 h is still fresh");
	g.clock.ms += 1;
	assert.deepEqual(await (await fetch(`${url}/webcam/clips`)).json(), { clips: [] });
	assert.deepEqual(cache(g.stateDir), ["harbor.mp4", "square.mp4"]);
});

test("a success entry without its file is not listed", async (t) => {
	quiet(t);
	const g = gateWith(t);
	await g.gate.captureWebcamCycle();
	fs.rmSync(path.join(g.stateDir, "webcam-cache", "square.mp4"));
	const url = await listen(t, g.gate.app);
	assert.deepEqual(await (await fetch(`${url}/webcam/clips`)).json(), { clips: ["harbor"] });
	assert.equal((await fetch(`${url}/webcam/clip/square`)).status, 404);
});

test("clip route: an unknown or unsafe camera id is 400", async (t) => {
	const g = gateWith(t);
	const url = await listen(t, g.gate.app);
	assert.equal((await fetch(`${url}/webcam/clip/other`)).status, 400);
	assert.equal((await fetch(`${url}/webcam/clip/harbor.tmp`)).status, 400);
	assert.equal((await fetch(`${url}/webcam/clip/..%2Fconfig`)).status, 400);
});

test("status shows each camera with its age and duration, and reads the old bare-number state", async (t) => {
	const g = gateWith(t);
	fs.writeFileSync(path.join(g.stateDir, "webcam-state.json"), JSON.stringify({ harbor: g.clock.ms - 5000, square: { at: g.clock.ms - 1000, durationSec: 12.5 } }));
	fs.mkdirSync(path.join(g.stateDir, "webcam-cache"), { recursive: true });
	fs.writeFileSync(path.join(g.stateDir, "webcam-cache", "square.mp4"), "x");
	const url = await listen(t, g.gate.app);
	const status = await (await fetch(`${url}/webcam/status`)).json();
	assert.deepEqual(status, {
		enabled: true,
		cycleInProgress: false,
		cameras: [
			{ id: "harbor", label: "Harbor", lastSuccessAt: g.clock.ms - 5000, ageMs: 5000, durationSec: null, available: false },
			{ id: "square", label: "Square", lastSuccessAt: g.clock.ms - 1000, ageMs: 1000, durationSec: 12.5, available: true },
		],
	});
});

test("status of a camera never captured, and a broken state file, show nulls", async (t) => {
	const g = gateWith(t);
	fs.writeFileSync(path.join(g.stateDir, "webcam-state.json"), "{broken");
	const url = await listen(t, g.gate.app);
	const [cam] = (await (await fetch(`${url}/webcam/status`)).json()).cameras;
	assert.deepEqual([cam.lastSuccessAt, cam.ageMs, cam.durationSec, cam.available], [null, null, null, false]);
});

test("the on/off switch: off empties the list, 404s clips, skips cycles and refuses refresh", async (t) => {
	const log = quiet(t);
	const g = gateWith(t);
	await g.gate.captureWebcamCycle();
	const url = await listen(t, g.gate.app);
	assert.deepEqual(await (await fetch(`${url}/webcam/enabled`)).json(), { enabled: true });
	assert.deepEqual(await (await post(url, "/webcam/enabled", { enabled: false })).json(), { enabled: false });
	assert.deepEqual(await (await fetch(`${url}/webcam/enabled`)).json(), { enabled: false });
	assert.deepEqual(await (await fetch(`${url}/webcam/clips`)).json(), { clips: [] });
	assert.equal((await fetch(`${url}/webcam/clip/harbor`)).status, 404);
	assert.deepEqual(await (await post(url, "/webcam/refresh")).json(), { status: "disabled" });
	const before = g.exec.calls.length;
	await g.gate.captureWebcamCycle();
	assert.equal(g.exec.calls.length, before);
	assert.match(log.log.join("\n"), /feature disabled/);
	assert.equal((await (await fetch(`${url}/webcam/status`)).json()).enabled, false);
});

test("only a JSON true switches the feature on", async (t) => {
	const g = gateWith(t);
	const url = await listen(t, g.gate.app);
	for (const body of [{ enabled: "true" }, { enabled: 1 }, {}]) {
		assert.deepEqual(await (await post(url, "/webcam/enabled", body)).json(), { enabled: false }, JSON.stringify(body));
	}
	assert.deepEqual(await (await post(url, "/webcam/enabled", { enabled: true })).json(), { enabled: true });
});

test("a scheduled cycle is skipped while the frame screen is off, a forced one is not", async (t) => {
	const log = quiet(t);
	const f = fakeFetch({ "GET /status": () => ({ body: { screenAwake: false } }) });
	const g = gateWith(t, { frameUrl: "http://frame.test:8099", fetch: f });
	await g.gate.captureWebcamCycle();
	assert.equal(g.exec.calls.length, 0);
	assert.match(log.log.join("\n"), /frame is asleep/);
	await g.gate.captureWebcamCycle(true);
	assert.ok(g.exec.calls.length > 0);
});

test("the frame counts as awake when its status is unknown (no URL, error status, unreachable, no field)", async (t) => {
	for (const [frameUrl, route] of [
		[undefined, null],
		["http://frame.test", () => ({ status: 500, body: "" })],
		["http://frame.test", () => ({ throws: new Error("ECONNREFUSED") })],
		["http://frame.test", () => ({ body: {} })],
	]) {
		quiet(t);
		const f = fakeFetch(route ? { "GET /status": route } : {});
		const g = gateWith(t, { frameUrl, fetch: f, config: { webcams: [CAMS[0]] } });
		await g.gate.captureWebcamCycle();
		assert.equal(g.exec.calls.filter((c) => c[0] === "ffmpeg").length, 1, String(frameUrl));
		t.mock.restoreAll();
	}
});

test("no cameras configured: a cycle does nothing", async (t) => {
	const g = makeGate(t);
	await g.gate.captureWebcamCycle(true);
	assert.equal(g.exec.calls.length, 0);
});

test("refresh starts a forced cycle; during it a second refresh and the status say it is running", async (t) => {
	quiet(t);
	let release;
	const gateOpen = new Promise((r) => { release = r; });
	const exec = { calls: [], fn: (cmd, args, opts, cb) => {
		if (typeof opts === "function") cb = opts;
		exec.calls.push([cmd, args]);
		if (cmd === "ffmpeg") fs.writeFileSync(args.at(-1), `c${exec.calls.length}`);
		gateOpen.then(() => cb(null, "30", ""));
	} };
	const g = gateWith(t, { exec });
	const url = await listen(t, g.gate.app);
	assert.deepEqual(await (await post(url, "/webcam/refresh")).json(), { status: "started" });
	assert.deepEqual(await (await post(url, "/webcam/refresh")).json(), { status: "already-running" });
	assert.equal((await (await fetch(`${url}/webcam/status`)).json()).cycleInProgress, true);
	release();
	await g.gate.whenWebcamIdle();
	assert.equal((await (await fetch(`${url}/webcam/status`)).json()).cycleInProgress, false);
	assert.equal(exec.calls.filter((c) => c[0] === "ffmpeg").length, 2);
});

test("two cycles started at once (start-up and timer) capture each camera only once", async (t) => {
	const log = quiet(t);
	const g = gateWith(t);
	await Promise.all([g.gate.captureWebcamCycle(), g.gate.captureWebcamCycle(true)]);
	assert.equal(g.exec.calls.filter((c) => c[0] === "ffmpeg").length, 2);
	assert.match(log.log.join("\n"), /a cycle is already running/);
});

test("a forced refresh while a scheduled cycle still asks the frame whether it sleeps does not start a second cycle", async (t) => {
	quiet(t);
	let answer;
	const statusAsked = new Promise((r) => { answer = r; });
	const f = fakeFetch({ "GET /status": () => statusAsked });
	const g = gateWith(t, { frameUrl: "http://frame.test", fetch: f });
	const url = await listen(t, g.gate.app);
	const scheduled = g.gate.captureWebcamCycle();
	const refresh = await (await post(url, "/webcam/refresh")).json();
	answer({ body: { screenAwake: true } });
	await scheduled;
	await g.gate.whenWebcamIdle();
	assert.deepEqual(refresh, { status: "already-running" });
	assert.equal(g.exec.calls.filter((c) => c[0] === "ffmpeg").length, 2, "each camera captured once");
});
