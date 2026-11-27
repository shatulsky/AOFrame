const test = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { isRepeatOfPrevious } = require("./frozen-stream");

function tempDir() {
	return fs.mkdtempSync(path.join(os.tmpdir(), "frozen-stream-"));
}

test("a clip identical to the previous one is a repeat", () => {
	const dir = tempDir();
	fs.writeFileSync(path.join(dir, "a.mp4"), Buffer.from([1, 2, 3, 4]));
	fs.writeFileSync(path.join(dir, "b.mp4"), Buffer.from([1, 2, 3, 4]));
	assert.strictEqual(isRepeatOfPrevious(path.join(dir, "b.mp4"), path.join(dir, "a.mp4")), true);
});

test("a clip with different bytes is not a repeat", () => {
	const dir = tempDir();
	fs.writeFileSync(path.join(dir, "a.mp4"), Buffer.from([1, 2, 3, 4]));
	fs.writeFileSync(path.join(dir, "b.mp4"), Buffer.from([1, 2, 3, 5]));
	assert.strictEqual(isRepeatOfPrevious(path.join(dir, "b.mp4"), path.join(dir, "a.mp4")), false);
});

test("a clip of the same size but different content is not a repeat", () => {
	const dir = tempDir();
	fs.writeFileSync(path.join(dir, "a.mp4"), Buffer.alloc(1000, 7));
	fs.writeFileSync(path.join(dir, "b.mp4"), Buffer.alloc(1000, 8));
	assert.strictEqual(isRepeatOfPrevious(path.join(dir, "b.mp4"), path.join(dir, "a.mp4")), false);
});

test("the first capture of a camera (no previous clip) is not a repeat", () => {
	const dir = tempDir();
	fs.writeFileSync(path.join(dir, "b.mp4"), Buffer.from([1]));
	assert.strictEqual(isRepeatOfPrevious(path.join(dir, "b.mp4"), path.join(dir, "missing.mp4")), false);
});
