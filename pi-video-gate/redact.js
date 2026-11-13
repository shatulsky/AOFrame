/* Masks secrets that appear inside URLs in log text: the query string
 * (stream tokens such as ?token=...) and the user:password part. ffmpeg
 * errors from execFile contain the whole command line, so a stream URL
 * with a token would otherwise reach the log, and from there Loki.
 */
const URL_PATTERN = /\b([a-z][a-z0-9+.-]*:\/\/)([^\s"'<>]*)/gi;

function redactUrl(scheme, rest) {
	let out = rest;
	const at = out.indexOf("@");
	const slash = out.search(/[/?#]/);
	if (at !== -1 && (slash === -1 || at < slash)) out = "<redacted>" + out.slice(at);
	const query = out.indexOf("?");
	if (query !== -1) out = out.slice(0, query) + "?<redacted>";
	return scheme + out;
}

function redactSecrets(text) {
	return String(text).replace(URL_PATTERN, (_match, scheme, rest) => redactUrl(scheme, rest));
}

// Every console line goes through redactSecrets, so no future log call can leak a URL secret.
function installConsoleRedaction(target = console) {
	for (const level of ["log", "info", "warn", "error", "debug"]) {
		const original = target[level].bind(target);
		target[level] = (...args) => original(...args.map((arg) => (typeof arg === "string" ? redactSecrets(arg) : arg)));
	}
}

module.exports = { redactSecrets, installConsoleRedaction };
