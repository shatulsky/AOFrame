/* Section A (video-ready gate) and section B (fps normalization webhook and
 * queue) of server.js, in-process with fakes (see gate-fixture.js).
 */
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const { ASSET, STILL, NEW_ASSET, fakeExec, fakeFetch, makeGate, listen, quiet } = require("./gate-fixture");

const fps = (value) => ({ ffprobe: () => ({ stdout: value }) });

function encodedFile(libraryDir, id) {
	const p = path.join(libraryDir, "encoded-video", "lib-1", id.slice(0, 2), id.slice(2, 4), `${id}.mp4`);
	fs.mkdirSync(path.dirname(p), { recursive: true });
	return p;
}

async function webhook(url, body) {
	return fetch(`${url}/immich-webhook`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
}

const videoEvent = (extra = {}) => ({ data: { asset: { id: ASSET, type: "VIDEO", originalPath: "/data/upload/a/clip.mov", originalFileName: "clip.mov", fileCreatedAt: "c", fileModifiedAt: "m", ...extra } } });

// Immich routes for a full normalization of ASSET.
function immichOk(extra = {}) {
	return fakeFetch({
		"POST /api/assets": () => ({ body: { id: NEW_ASSET } }),
		"DELETE /api/assets": () => ({ body: "" }),
		"PUT /api/jobs/videoConversion": () => ({ body: {} }),
		...extra,
	});
}

// --- A. gate ------------------------------------------------------------

test("gate: a bad id is 400, an unknown one 404", async (t) => {
	const { gate } = makeGate(t);
	const url = await listen(t, gate.app);
	assert.equal((await fetch(`${url}/video/not-a-uuid`)).status, 400);
	assert.equal((await fetch(`${url}/video/${ASSET}`)).status, 404);
});

test("gate: an encoded file is served as video/mp4", async (t) => {
	const { gate, libraryDir } = makeGate(t);
	fs.writeFileSync(encodedFile(libraryDir, ASSET), "ENCODED");
	const url = await listen(t, gate.app);
	const res = await fetch(`${url}/video/${ASSET}`);
	assert.equal(res.headers.get("content-type"), "video/mp4");
	assert.equal(await res.text(), "ENCODED");
});

test("gate: a confirmed-safe original is served when Immich made no encoded file", async (t) => {
	const { gate, stateDir, root } = makeGate(t);
	const original = path.join(root, "orig.mp4");
	fs.writeFileSync(original, "ORIGINAL");
	fs.writeFileSync(path.join(stateDir, "confirmed.json"), JSON.stringify({ [ASSET]: original }));
	const url = await listen(t, gate.app);
	assert.equal(await (await fetch(`${url}/video/${ASSET}`)).text(), "ORIGINAL");
});

test("gate: the encoded file wins over the confirmed original", async (t) => {
	const { gate, stateDir, root, libraryDir } = makeGate(t);
	fs.writeFileSync(encodedFile(libraryDir, ASSET), "ENCODED");
	fs.writeFileSync(path.join(root, "orig.mp4"), "ORIGINAL");
	fs.writeFileSync(path.join(stateDir, "confirmed.json"), JSON.stringify({ [ASSET]: path.join(root, "orig.mp4") }));
	const url = await listen(t, gate.app);
	assert.equal(await (await fetch(`${url}/video/${ASSET}`)).text(), "ENCODED");
});

test("gate: a confirmed path whose file is gone is 404", async (t) => {
	const { gate, stateDir, root } = makeGate(t);
	fs.writeFileSync(path.join(stateDir, "confirmed.json"), JSON.stringify({ [ASSET]: path.join(root, "gone.mp4") }));
	const url = await listen(t, gate.app);
	assert.equal((await fetch(`${url}/video/${ASSET}`)).status, 404);
});

test("gate: no library id folder still serves a confirmed original, and logs the error", async (t) => {
	const log = quiet(t);
	const { gate, stateDir, root, libraryDir } = makeGate(t);
	fs.rmSync(path.join(libraryDir, "encoded-video", "lib-1"), { recursive: true });
	fs.writeFileSync(path.join(root, "orig.mp4"), "ORIGINAL");
	fs.writeFileSync(path.join(stateDir, "confirmed.json"), JSON.stringify({ [ASSET]: path.join(root, "orig.mp4") }));
	const url = await listen(t, gate.app);
	assert.equal(await (await fetch(`${url}/video/${ASSET}`)).text(), "ORIGINAL");
	assert.match(log.error.join("\n"), /no library id directory/);
});

test("gate: a broken confirmed.json reads as empty", async (t) => {
	const { gate, stateDir } = makeGate(t);
	fs.writeFileSync(path.join(stateDir, "confirmed.json"), "{oops");
	const url = await listen(t, gate.app);
	assert.equal((await fetch(`${url}/video/${ASSET}`)).status, 404);
});

// --- B. webhook and queue -------------------------------------------------

test("webhook: answers queued at once, even for a payload without an asset", async (t) => {
	const log = quiet(t);
	const { gate, exec } = makeGate(t);
	const url = await listen(t, gate.app);
	assert.deepEqual(await (await webhook(url, { foo: 1 })).json(), { status: "queued" });
	await gate.whenIdle();
	assert.equal(exec.calls.length, 0);
	assert.match(log.warn.join("\n"), /could not extract an asset/);
});

test("webhook: an image without a live photo clip is ignored, with no error", async (t) => {
	const log = quiet(t);
	const { gate, exec } = makeGate(t);
	const url = await listen(t, gate.app);
	await webhook(url, { data: { asset: { id: ASSET, type: "IMAGE" } } });
	await gate.whenIdle();
	assert.equal(exec.calls.length, 0);
	assert.deepEqual(log.error, []);
});

test("a 30 fps (or 29.97) video is only probed and recorded as confirmed safe", async (t) => {
	quiet(t);
	for (const [out, label] of [["30/1\n", "30"], ["30000/1001,\n", "29.97 with csv comma"], ["25\n", "bare number"]]) {
		const { gate, exec, fetch: f, stateDir, libraryDir } = makeGate(t, { exec: fakeExec(fps(out)) });
		gate.enqueue(videoEvent().data.asset);
		await gate.whenIdle();
		assert.deepEqual(exec.calls.map((c) => c[0]), ["ffprobe"], label);
		assert.equal(exec.calls[0][1].at(-1), `${libraryDir}/upload/a/clip.mov`, label);
		assert.equal(f.calls.length, 0, label);
		const confirmed = JSON.parse(fs.readFileSync(path.join(stateDir, "confirmed.json"), "utf8"));
		assert.deepEqual(confirmed, { [ASSET]: `${libraryDir}/upload/a/clip.mov` }, label);
	}
});

test("a 60 fps video is normalized: ffmpeg under nice, upload, then delete, then re-transcode", async (t) => {
	quiet(t);
	const { gate, exec, fetch: f, stateDir } = makeGate(t, { exec: fakeExec(fps("60/1,\n")), fetch: immichOk() });
	gate.enqueue(videoEvent().data.asset);
	await gate.whenIdle();
	const ffmpeg = exec.calls.find((c) => c[0] === "nice")[1];
	assert.deepEqual(ffmpeg.slice(0, 4), ["-n", "15", "ffmpeg", "-y"]);
	assert.ok(ffmpeg.includes("-threads") && ffmpeg.includes("30"));
	assert.deepEqual(f.calls.map((c) => `${c.method} ${c.path}`), ["POST /api/assets", "DELETE /api/assets", "PUT /api/jobs/videoConversion"]);
	const form = f.calls[0].init.body;
	assert.equal(form.get("filename"), "clip.mov");
	assert.equal(form.get("fileCreatedAt"), "c");
	assert.equal(form.get("fileModifiedAt"), "m");
	assert.equal(f.calls[0].init.headers["x-api-key"], "test-key");
	assert.deepEqual(JSON.parse(f.calls[1].init.body), { ids: [ASSET], force: true });
	assert.deepEqual(fs.readdirSync(path.join(stateDir, "tmp")), []);
});

test("upload metadata falls back to now and the file name when the payload has none", async (t) => {
	quiet(t);
	const { gate, fetch: f, clock } = makeGate(t, { exec: fakeExec(fps("60")), fetch: immichOk() });
	gate.enqueue({ id: ASSET, type: "VIDEO", originalPath: "/data/x.mp4" });
	await gate.whenIdle();
	const form = f.calls[0].init.body;
	assert.equal(form.get("filename"), `${ASSET}-normalized.mp4`);
	assert.equal(form.get("fileCreatedAt"), new Date(form.get("fileCreatedAt")).toISOString());
	assert.ok(clock.ms > 0);
});

test("the original is never deleted when the upload fails", async (t) => {
	const log = quiet(t);
	const { gate, fetch: f, sleeps } = makeGate(t, {
		exec: fakeExec(fps("60")),
		fetch: immichOk({ "POST /api/assets": () => ({ status: 500, body: "disk full" }) }),
	});
	gate.enqueue(videoEvent().data.asset);
	await gate.whenIdle();
	assert.equal(f.calls.filter((c) => c.method === "DELETE").length, 0);
	assert.equal(f.calls.filter((c) => c.method === "POST").length, 3);
	assert.deepEqual(sleeps, [8000, 8000]);
	assert.match(log.error.join("\n"), /upload failed: 500 disk full/);
	assert.match(log.error.join("\n"), /FAILED after 3 attempts/);
});

test("a failed attempt leaves no normalized file in tmp/", async (t) => {
	quiet(t);
	const { gate, stateDir } = makeGate(t, {
		exec: fakeExec(fps("60")),
		fetch: immichOk({ "POST /api/assets": () => ({ status: 500, body: "x" }) }),
	});
	gate.enqueue(videoEvent().data.asset);
	await gate.whenIdle();
	assert.deepEqual(fs.readdirSync(path.join(stateDir, "tmp")), []);
});

test("an ffprobe error or odd output is retried three times, then the asset is left to Immich", async (t) => {
	for (const [script, why] of [[{ ffprobe: () => ({ error: new Error("probe died") }) }, /failed: probe died/], [fps("N/A\n"), /unexpected ffprobe output/]]) {
		const log = quiet(t);
		const { gate, exec, fetch: f } = makeGate(t, { exec: fakeExec(script) });
		gate.enqueue(videoEvent().data.asset);
		await gate.whenIdle();
		assert.equal(exec.calls.length, 3);
		assert.equal(f.calls.length, 0);
		assert.match(log.error.join("\n"), why);
		t.mock.restoreAll();
	}
});

test("a delete or job trigger failure is retried and logged", async (t) => {
	for (const [route, text] of [["DELETE /api/assets", /delete failed: 403/], ["PUT /api/jobs/videoConversion", /job trigger failed: 403/]]) {
		const log = quiet(t);
		const { gate } = makeGate(t, { exec: fakeExec(fps("60")), fetch: immichOk({ [route]: () => ({ status: 403, body: "no" }) }) });
		gate.enqueue(videoEvent().data.asset);
		await gate.whenIdle();
		assert.match(log.error.join("\n"), text);
		t.mock.restoreAll();
	}
});

test("a duplicate webhook for a queued asset is ignored; after it finished, a new one runs again", async (t) => {
	const log = quiet(t);
	const { gate, exec } = makeGate(t, { exec: fakeExec(fps("30")) });
	const asset = videoEvent().data.asset;
	gate.enqueue(asset);
	gate.enqueue(asset);
	await gate.whenIdle();
	assert.equal(exec.calls.length, 1);
	assert.match(log.log.join("\n"), /already queued\/in-flight/);
	gate.enqueue(asset);
	await gate.whenIdle();
	assert.equal(exec.calls.length, 2);
});

test("assets are processed one at a time, in order", async (t) => {
	quiet(t);
	let running = 0;
	let most = 0;
	const order = [];
	const exec = fakeExec();
	const slowProbe = (cmd, args, cb) => {
		running++; most = Math.max(most, running); order.push(args.at(-1));
		setTimeout(() => { running--; cb(null, "30", ""); }, 5);
	};
	const { gate } = makeGate(t, { exec: { fn: slowProbe, calls: exec.calls } });
	for (const id of ["1", "2", "3"]) gate.enqueue({ id: `${ASSET.slice(0, -1)}${id}`, type: "VIDEO", originalPath: `/data/${id}` });
	await gate.whenIdle();
	assert.equal(most, 1);
	assert.deepEqual(order.map((p) => p.slice(-1)), ["1", "2", "3"]);
});

test("a live photo still: the clip is found via Immich and Postgres, normalized and re-paired", async (t) => {
	quiet(t);
	const exec = fakeExec({ ...fps("60"), docker: () => ({ stdout: "  /data/upload/x/clip.mov\n" }) });
	const f = immichOk({
		[`GET /api/assets/${ASSET}`]: () => ({ body: { originalFileName: "IMG_1.MOV", fileCreatedAt: "c2", fileModifiedAt: "m2" } }),
		[`PATCH /api/assets/${STILL}`]: () => ({ body: {} }),
	});
	const { gate, libraryDir } = makeGate(t, { exec, fetch: f });
	gate.enqueue({ id: STILL, type: "IMAGE", livePhotoVideoId: ASSET });
	await gate.whenIdle();
	const sql = exec.calls.find((c) => c[0] === "docker")[1].at(-1);
	assert.equal(sql, `SELECT "originalPath" FROM asset WHERE id = '${ASSET}';`);
	assert.equal(exec.calls.find((c) => c[0] === "ffprobe")[1].at(-1), `${libraryDir}/upload/x/clip.mov`);
	assert.equal(f.calls.find((c) => c.method === "POST").init.body.get("filename"), "IMG_1.MOV");
	assert.deepEqual(JSON.parse(f.calls.find((c) => c.method === "DELETE").init.body).ids, [ASSET]);
	const patch = f.calls.find((c) => c.method === "PATCH");
	assert.deepEqual(JSON.parse(patch.init.body), { livePhotoVideoId: NEW_ASSET });
});

test("a live photo clip id that is not a UUID never reaches the SQL command", async (t) => {
	const log = quiet(t);
	const exec = fakeExec();
	const f = fakeFetch({ "GET /api/assets/x';DROP--": () => ({ body: {} }) });
	const { gate } = makeGate(t, { exec, fetch: f });
	gate.enqueue({ id: STILL, type: "IMAGE", livePhotoVideoId: "x';DROP--" });
	await gate.whenIdle();
	assert.equal(exec.calls.filter((c) => c[0] === "docker").length, 0);
	assert.match(log.error.join("\n"), /invalid asset id/);
});

test("a live photo with no row in Postgres, or a failed lookup, is retried and logged", async (t) => {
	for (const [docker, why] of [[() => ({ stdout: "\n" }), /no originalPath found/], [() => ({ error: new Error("no container") }), /failed: no container/]]) {
		const log = quiet(t);
		const f = fakeFetch({ [`GET /api/assets/${ASSET}`]: () => ({ body: {} }) });
		const { gate } = makeGate(t, { exec: fakeExec({ docker }), fetch: f });
		gate.enqueue({ id: STILL, type: "IMAGE", livePhotoVideoId: ASSET });
		await gate.whenIdle();
		assert.match(log.error.join("\n"), why);
		t.mock.restoreAll();
	}
});

test("a live photo whose asset lookup fails is retried and logged", async (t) => {
	const log = quiet(t);
	const f = fakeFetch({ [`GET /api/assets/${ASSET}`]: () => ({ status: 404, body: "" }) });
	const { gate } = makeGate(t, { fetch: f });
	gate.enqueue({ id: STILL, type: "IMAGE", livePhotoVideoId: ASSET });
	await gate.whenIdle();
	assert.match(log.error.join("\n"), new RegExp(`GET /api/assets/${ASSET} failed: 404`));
});

test("a failed re-pairing is retried and logged", async (t) => {
	const log = quiet(t);
	const f = immichOk({
		[`GET /api/assets/${ASSET}`]: () => ({ body: {} }),
		[`PATCH /api/assets/${STILL}`]: () => ({ status: 400, body: "bad" }),
	});
	const { gate } = makeGate(t, { exec: fakeExec({ ...fps("60"), docker: () => ({ stdout: "/data/v.mov" }) }), fetch: f });
	gate.enqueue({ id: STILL, type: "IMAGE", livePhotoVideoId: ASSET });
	await gate.whenIdle();
	assert.match(log.error.join("\n"), /live photo re-pairing failed: 400 bad/);
});

test("whenIdle settles at once when nothing is queued", async (t) => {
	const { gate } = makeGate(t);
	await gate.whenIdle();
});
