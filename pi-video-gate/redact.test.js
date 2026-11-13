const test = require("node:test");
const assert = require("node:assert");
const { redactSecrets, installConsoleRedaction } = require("./redact");

test("masks the query string of a stream URL", () => {
	const text = "Command failed: ffmpeg -y -i https://cam.example.com/live/index.m3u8?token=abc123&x=1 -t 5 out.mp4";
	const out = redactSecrets(text);
	assert.ok(!out.includes("abc123"));
	assert.ok(out.includes("https://cam.example.com/live/index.m3u8?<redacted>"));
	assert.ok(out.includes("-t 5 out.mp4"));
});

test("masks user and password in a URL", () => {
	const out = redactSecrets("open rtsp://admin:s3cret@192.168.1.5:554/stream failed");
	assert.ok(!out.includes("s3cret"));
	assert.ok(!out.includes("admin"));
	assert.ok(out.includes("rtsp://<redacted>@192.168.1.5:554/stream"));
});

test("leaves plain text and URLs without secrets alone", () => {
	assert.strictEqual(redactSecrets("[webcam] harbor refreshed (5.0s)"), "[webcam] harbor refreshed (5.0s)");
	assert.strictEqual(redactSecrets("GET http://localhost:2283/api/assets failed"), "GET http://localhost:2283/api/assets failed");
});

test("an @ after the path is not user info", () => {
	assert.strictEqual(redactSecrets("http://host/a@b"), "http://host/a@b");
});

test("masks every URL in a multi-line ffmpeg error", () => {
	const out = redactSecrets("one https://a/x?token=t1\ntwo https://b/y?sig=t2 done");
	assert.ok(!out.includes("t1") && !out.includes("t2"));
	assert.ok(out.includes("\ntwo "));
});

test("console wrapper masks string arguments", () => {
	const lines = [];
	const fake = { log: (...a) => lines.push(a.join(" ")), info() {}, warn() {}, error() {}, debug() {} };
	installConsoleRedaction(fake);
	fake.log("grab failed https://c/s.m3u8?token=zzz");
	assert.strictEqual(lines[0], "grab failed https://c/s.m3u8?<redacted>");
});
