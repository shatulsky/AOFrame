const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const { startApp } = require("./helpers");

// fakeTranscode: writes a marker file instead of running ffmpeg, and records each call.
function fakeTranscode({ fail = false } = {}) {
	const calls = [];
	const fn = async (input, output) => {
		calls.push([path.basename(input), path.basename(output)]);
		if (fail) throw new Error("ffmpeg exited with 1");
		fs.writeFileSync(output, "normalized");
	};
	return { fn, calls };
}

async function upload(app, name, type, bytes = "x") {
	const form = new FormData();
	form.append("photo", new Blob([bytes], { type }), name);
	return fetch(app.url + "/api/photos", { method: "POST", body: form });
}

async function setup(t, transcode = fakeTranscode()) {
	// Port 9 (discard) is never asked: photo routes do not call the frame.
	const app = await startApp({ frameControlUrl: "http://127.0.0.1:9" }, { transcode: transcode.fn });
	t.after(app.close);
	return { app, transcode };
}

const files = (dir) => fs.readdirSync(dir).sort();
const JSON_HEADERS = { "Content-Type": "application/json" };

test("an image upload is stored by id with its extension and listed", async (t) => {
	const { app, transcode } = await setup(t);
	const res = await upload(app, "beach.jpg", "image/jpeg", "JPEGDATA");
	assert.equal(res.status, 201);
	const body = await res.json();
	assert.match(body.id, /^[0-9a-f-]{36}$/);
	assert.equal(body.ext, ".jpg");
	assert.equal(body.originalName, "beach.jpg");
	assert.equal(transcode.calls.length, 0);
	assert.deepEqual(files(app.dir), [`${body.id}.jpg`, "manifest.json"]);
	const list = await (await fetch(app.url + "/api/photos")).json();
	assert.deepEqual(list.map((p) => p.id), [body.id]);
	assert.equal(await (await fetch(`${app.url}/api/photos/${body.id}`)).text(), "JPEGDATA");
});

test("a video upload is transcoded and only the normalized .mp4 stays", async (t) => {
	const { app, transcode } = await setup(t);
	const body = await (await upload(app, "clip.MOV", "video/mp4")).json();
	assert.equal(body.ext, ".mp4");
	assert.deepEqual(transcode.calls, [[`${body.id}.MOV`, `${body.id}.normalized.mp4`]]);
	assert.deepEqual(files(app.dir), [`${body.id}.mp4`, "manifest.json"]);
	assert.equal(await (await fetch(`${app.url}/api/photos/${body.id}`)).text(), "normalized");
});

test("a video already named .mp4 is replaced in place by the normalized file", async (t) => {
	const { app } = await setup(t);
	const body = await (await upload(app, "clip.mp4", "video/mp4", "raw")).json();
	assert.deepEqual(files(app.dir), [`${body.id}.mp4`, "manifest.json"]);
	assert.equal(fs.readFileSync(path.join(app.dir, `${body.id}.mp4`), "utf8"), "normalized");
});

test("a failed transcode answers 400, removes both files and adds nothing to the manifest", async (t) => {
	const { app } = await setup(t, fakeTranscode({ fail: true }));
	const res = await upload(app, "hdr.mp4", "video/mp4");
	assert.equal(res.status, 400);
	assert.match((await res.json()).error, /video could not be processed: ffmpeg exited with 1/);
	assert.deepEqual(files(app.dir), []);
	assert.deepEqual(await (await fetch(app.url + "/api/photos")).json(), []);
});

test("a file type outside the list, or no file, is refused with 400 and nothing is stored", async (t) => {
	const { app } = await setup(t);
	assert.equal((await upload(app, "doc.pdf", "application/pdf")).status, 400);
	assert.equal((await upload(app, "a.mov", "video/quicktime")).status, 400);
	assert.equal((await fetch(app.url + "/api/photos", { method: "POST", body: new FormData() })).status, 400);
	assert.deepEqual(files(app.dir), []);
});

test("face target: numbers 0-100 are saved, anything else is 400", async (t) => {
	const { app } = await setup(t);
	const { id } = await (await upload(app, "a.png", "image/png")).json();
	const patch = (body) => fetch(`${app.url}/api/photos/${id}/face`, { method: "PATCH", headers: JSON_HEADERS, body: JSON.stringify(body) });
	for (const bad of [{ faceX: 50 }, { faceX: "50", faceY: 50 }, { faceX: -1, faceY: 50 }, { faceX: 50, faceY: 100.5 }]) {
		assert.equal((await patch(bad)).status, 400, JSON.stringify(bad));
	}
	const ok = await patch({ faceX: 0, faceY: 100 });
	assert.equal(ok.status, 200);
	const saved = await ok.json();
	assert.deepEqual([saved.faceX, saved.faceY], [0, 100]);
	const [listed] = await (await fetch(app.url + "/api/photos")).json();
	assert.deepEqual([listed.faceX, listed.faceY], [0, 100]);
});

test("delete removes the file and the entry; a second delete is 404", async (t) => {
	const { app } = await setup(t);
	const { id } = await (await upload(app, "a.gif", "image/gif")).json();
	assert.equal((await fetch(`${app.url}/api/photos/${id}`, { method: "DELETE" })).status, 204);
	assert.deepEqual(files(app.dir), ["manifest.json"]);
	assert.equal((await fetch(`${app.url}/api/photos/${id}`, { method: "DELETE" })).status, 404);
});

test("an unknown id is 404 on read, face and delete", async (t) => {
	const { app } = await setup(t);
	assert.equal((await fetch(`${app.url}/api/photos/nope`)).status, 404);
	const face = await fetch(`${app.url}/api/photos/nope/face`, { method: "PATCH", headers: JSON_HEADERS, body: JSON.stringify({ faceX: 1, faceY: 1 }) });
	assert.equal(face.status, 404);
	assert.equal((await fetch(`${app.url}/api/photos/nope`, { method: "DELETE" })).status, 404);
});

test("ids that are names of Object members are unknown ids and never touch Object.prototype", async (t) => {
	const { app } = await setup(t);
	for (const id of ["__proto__", "constructor", "toString", "hasOwnProperty"]) {
		const face = await fetch(`${app.url}/api/photos/${id}/face`, { method: "PATCH", headers: JSON_HEADERS, body: JSON.stringify({ faceX: 11, faceY: 22 }) });
		assert.equal(face.status, 404, `PATCH ${id}`);
		assert.equal((await fetch(`${app.url}/api/photos/${id}`)).status, 404, `GET ${id}`);
		assert.equal((await fetch(`${app.url}/api/photos/${id}`, { method: "DELETE" })).status, 404, `DELETE ${id}`);
	}
	assert.equal({}.faceX, undefined);
});

test("a broken manifest file reads as an empty library", async (t) => {
	const { app } = await setup(t);
	fs.writeFileSync(path.join(app.dir, "manifest.json"), "{not json");
	assert.deepEqual(await (await fetch(app.url + "/api/photos")).json(), []);
});
