/* Detects a camera stream that has stopped moving. A stuck camera (or
 * its relay) keeps answering, so ffmpeg copies it without any error, but
 * every capture holds the same old footage. Live footage never repeats
 * byte for byte, so a new clip that is identical to the previous one
 * means the source is frozen.
 */
const crypto = require("crypto");
const fs = require("fs");

function fileSha256(filePath) {
	return crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

// True when both files exist and hold the same bytes. A missing previous
// clip (first capture of a camera) is never a repeat.
function isRepeatOfPrevious(newClipPath, previousClipPath) {
	if (!fs.existsSync(previousClipPath)) return false;
	return fileSha256(newClipPath) === fileSha256(previousClipPath);
}

module.exports = { isRepeatOfPrevious };
