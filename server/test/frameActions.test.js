const test = require("node:test");
const assert = require("node:assert/strict");
const { startFakeFrame, startApp } = require("./helpers");

async function setup(t, answer) {
	const frame = await startFakeFrame(answer);
	const app = await startApp({ frameControlUrl: frame.url });
	t.after(async () => { await app.close(); await frame.close(); });
	return { frame, app };
}

const CONFIG_ROUTES = ["/action/night-mode", "/action/countdown", "/action/slideshow-settings", "/action/webcam-test-mode"];
const FIRE_ROUTES = ["/action/refresh-cache", "/action/reset-faces", "/action/reshuffle", "/action/immich-webhook", "/action/refresh-webcam"];
const READ_ROUTES = ["/action/webcam-sync-status", "/status"];
const JSON_HEADERS = { "Content-Type": "application/json" };

test("config routes relay GET and POST with the body to the same path on the frame", async (t) => {
	const { frame, app } = await setup(t, (req, body) => ({ body: { path: req.url, method: req.method, got: body ? JSON.parse(body) : null } }));
	for (const route of CONFIG_ROUTES) {
		assert.deepEqual(await (await fetch(app.url + route)).json(), { path: route, method: "GET", got: null });
		const res = await fetch(app.url + route, { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ enabled: true }) });
		assert.deepEqual(await res.json(), { path: route, method: "POST", got: { enabled: true } });
	}
	assert.equal(frame.requests.length, CONFIG_ROUTES.length * 2);
});

test("fire-and-forget routes are POST only and send no body", async (t) => {
	const { frame, app } = await setup(t, () => ({ body: { ok: true } }));
	for (const route of FIRE_ROUTES) {
		const res = await fetch(app.url + route, { method: "POST" });
		assert.equal(res.status, 200, route);
		assert.deepEqual(await res.json(), { ok: true });
		assert.equal((await fetch(app.url + route)).status, 404, `GET ${route}`);
	}
	assert.deepEqual(frame.requests.map((r) => [r.method, r.url, r.body]), FIRE_ROUTES.map((r) => ["POST", r, ""]));
});

test("read-only routes are GET only", async (t) => {
	const { frame, app } = await setup(t, () => ({ body: { up: 1 } }));
	for (const route of READ_ROUTES) {
		assert.deepEqual(await (await fetch(app.url + route)).json(), { up: 1 });
		assert.equal((await fetch(app.url + route, { method: "POST" })).status, 404, `POST ${route}`);
	}
	assert.equal(frame.requests.length, READ_ROUTES.length);
});

test("a frame error becomes 502 with the frame's message, on every kind of route", async (t) => {
	const { app } = await setup(t, () => ({ status: 500, body: "boom" }));
	const cases = [["/action/night-mode", "GET"], ["/action/night-mode", "POST"], ["/action/reshuffle", "POST"], ["/status", "GET"]];
	for (const [route, method] of cases) {
		const res = await fetch(app.url + route, { method, headers: JSON_HEADERS, body: method === "POST" ? "{}" : undefined });
		assert.equal(res.status, 502, `${method} ${route}`);
		assert.deepEqual(await res.json(), { error: "Frame returned 500: boom" });
	}
});

test("an unreachable frame becomes 502, not a crash", async (t) => {
	const frame = await startFakeFrame();
	const url = frame.url;
	await frame.close();
	const app = await startApp({ frameControlUrl: url });
	t.after(app.close);
	const res = await fetch(app.url + "/status");
	assert.equal(res.status, 502);
	assert.match((await res.json()).error, /ECONNREFUSED/);
});

test("the dashboard page is served at / and calls only routes this server has", async (t) => {
	const { app } = await setup(t);
	const res = await fetch(app.url + "/");
	assert.match(res.headers.get("content-type"), /text\/html/);
	const page = await res.text();
	for (const route of ["/status", "/action/night-mode", "/action/countdown", "/api/photos"]) {
		assert.ok(page.includes(`"${route}`), route);
	}
	const actions = [...page.matchAll(/action\('([a-z-]+)'\)/g)].map((m) => m[1]);
	assert.deepEqual(actions, ["refresh-cache", "reset-faces", "reshuffle", "refresh-webcam"]);
	for (const name of actions) assert.ok(FIRE_ROUTES.includes(`/action/${name}`), name);
});

test("a caller outside the whitelist gets 403 and the frame sees nothing", async (t) => {
	const frame = await startFakeFrame();
	const app = await startApp({ frameControlUrl: frame.url, ipWhitelist: ["192.0.2.1"] });
	t.after(async () => { await app.close(); await frame.close(); });
	const res = await fetch(app.url + "/status");
	assert.equal(res.status, 403);
	assert.equal(frame.requests.length, 0);
});
