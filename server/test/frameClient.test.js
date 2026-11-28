const test = require("node:test");
const assert = require("node:assert/strict");
const { createFrameClient, DEFAULT_FRAME_CONTROL_URL } = require("../lib/frameClient");
const { startFakeFrame } = require("./helpers");

test("GET returns the parsed JSON of the frame", async (t) => {
	const frame = await startFakeFrame(() => ({ body: { enabled: true } }));
	t.after(frame.close);
	assert.deepEqual(await createFrameClient(frame.url)("/action/night-mode", "GET"), { enabled: true });
	assert.equal(frame.requests[0].method, "GET");
	assert.equal(frame.requests[0].body, "");
});

test("POST sends the body as JSON with its content type", async (t) => {
	const frame = await startFakeFrame((req, body) => ({ body: JSON.parse(body) }));
	t.after(frame.close);
	const echoed = await createFrameClient(frame.url)("/action/countdown", "POST", { label: "Trip ü" });
	assert.deepEqual(echoed, { label: "Trip ü" });
	assert.equal(frame.requests[0].contentType, "application/json");
});

test("an empty 200 answer is an empty object", async (t) => {
	const frame = await startFakeFrame(() => ({ body: "" }));
	t.after(frame.close);
	assert.deepEqual(await createFrameClient(frame.url)("/action/reshuffle", "POST"), {});
});

test("a 4xx or 5xx answer rejects with the status and the text", async (t) => {
	const frame = await startFakeFrame(() => ({ status: 400, body: "bad time" }));
	t.after(frame.close);
	await assert.rejects(createFrameClient(frame.url)("/action/night-mode", "POST", {}), /Frame returned 400: bad time/);
});

test("a 2xx answer that is not JSON rejects", async (t) => {
	const frame = await startFakeFrame(() => ({ body: "<html>" }));
	t.after(frame.close);
	await assert.rejects(createFrameClient(frame.url)("/status", "GET"), /invalid JSON: <html>/);
});

test("a frame that does not answer rejects with the socket error", async () => {
	const frame = await startFakeFrame();
	const url = frame.url;
	await frame.close();
	await assert.rejects(createFrameClient(url)("/status", "GET"), { code: "ECONNREFUSED" });
});

test("without a configured URL the client uses the frame's default port on loopback", () => {
	assert.equal(DEFAULT_FRAME_CONTROL_URL, "http://127.0.0.1:8099");
});
