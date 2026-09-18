/* Basic stats/actions dashboard - a single static page whose JS talks to
 * this server's own JSON routes (routes/frame-actions.js, routes/photos.js)
 * via fetch. Deliberately no build step/framework - a plain page is
 * enough for a LAN-only admin tool, and it keeps this reference server's
 * dependency surface small.
 */
module.exports = function registerDashboardRoutes(app) {
	app.get("/", (req, res) => {
		res.set("Content-Type", "text/html; charset=utf-8").send(PAGE);
	});
};

const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AOFrame server</title>
<style>
	:root { color-scheme: dark; }
	body { font-family: sans-serif; max-width: 720px; margin: 32px auto; padding: 0 16px; background: #14161a; color: #e6e6e6; }
	h1 { font-size: 20px; }
	h2 { font-size: 16px; margin-top: 32px; border-bottom: 1px solid #333; padding-bottom: 4px; }
	button { padding: 8px 14px; margin: 4px 8px 4px 0; font-size: 14px; cursor: pointer; }
	pre { background: #1e2126; padding: 12px; overflow-x: auto; font-size: 13px; }
	.row { margin: 8px 0; }
	label { display: inline-block; min-width: 90px; }
	input[type="text"] { padding: 4px; }
	#photoList img, #photoList video { max-width: 120px; max-height: 120px; margin: 4px; border-radius: 4px; }
</style>
</head>
<body>
<h1>AOFrame server</h1>
<p>Reference admin panel - proxies the frame's own LocalControlServer and hosts the CRUD photo API for local (Immich-optional) mode.</p>

<h2>Status</h2>
<pre id="status">loading...</pre>
<button onclick="refresh()">Refresh</button>

<h2>Actions</h2>
<div class="row">
	<button onclick="action('refresh-cache')">Refresh cache</button>
	<button onclick="action('reset-faces')">Reset faces</button>
	<button onclick="action('reshuffle')">Reshuffle</button>
	<button onclick="action('refresh-webcam')">Refresh webcam</button>
</div>
<pre id="actionResult"></pre>

<h2>Night mode</h2>
<div class="row">
	<label>Enabled</label><input type="checkbox" id="nmEnabled">
	<label>Sleep</label><input type="text" id="nmSleep" placeholder="01:00">
	<label>Wake</label><input type="text" id="nmWake" placeholder="08:00">
	<button onclick="saveNightMode()">Save</button>
</div>

<h2>Countdown</h2>
<div class="row">
	<label>Enabled</label><input type="checkbox" id="cdEnabled">
	<label>Target</label><input type="text" id="cdTarget" placeholder="2027-10-02T00:00">
	<label>Label</label><input type="text" id="cdLabel">
	<button onclick="saveCountdown()">Save</button>
</div>

<h2>Photos &amp; videos (local mode)</h2>
<div class="row">
	<input type="file" id="photoFile" accept="image/*,video/mp4">
	<button onclick="uploadPhoto()">Upload</button>
</div>
<div id="photoList"></div>

<script>
async function refresh() {
	const status = document.getElementById("status");
	try {
		const res = await fetch("/status");
		status.textContent = JSON.stringify(await res.json(), null, 2);
	} catch (error) {
		status.textContent = "Frame unreachable: " + error.message;
	}
	loadNightMode();
	loadCountdown();
	loadPhotos();
}

async function action(name) {
	const result = document.getElementById("actionResult");
	try {
		const res = await fetch("/action/" + name, { method: "POST" });
		result.textContent = JSON.stringify(await res.json());
	} catch (error) {
		result.textContent = "Failed: " + error.message;
	}
}

async function loadNightMode() {
	try {
		const config = await (await fetch("/action/night-mode")).json();
		document.getElementById("nmEnabled").checked = !!config.enabled;
		document.getElementById("nmSleep").value = config.sleepTime || "";
		document.getElementById("nmWake").value = config.wakeTime || "";
	} catch (error) { /* frame unreachable - status box above already shows this */ }
}

async function saveNightMode() {
	await fetch("/action/night-mode", {
		method: "POST",
		headers: { "Content-Type": "application/json" },
		body: JSON.stringify({
			enabled: document.getElementById("nmEnabled").checked,
			sleepTime: document.getElementById("nmSleep").value,
			wakeTime: document.getElementById("nmWake").value
		})
	});
	loadNightMode();
}

async function loadCountdown() {
	try {
		const config = await (await fetch("/action/countdown")).json();
		document.getElementById("cdEnabled").checked = !!config.enabled;
		document.getElementById("cdTarget").value = config.targetDate || "";
		document.getElementById("cdLabel").value = config.label || "";
	} catch (error) { /* frame unreachable - status box above already shows this */ }
}

async function saveCountdown() {
	await fetch("/action/countdown", {
		method: "POST",
		headers: { "Content-Type": "application/json" },
		body: JSON.stringify({
			enabled: document.getElementById("cdEnabled").checked,
			targetDate: document.getElementById("cdTarget").value,
			label: document.getElementById("cdLabel").value,
			precision: "full"
		})
	});
	loadCountdown();
}

async function loadPhotos() {
	const list = document.getElementById("photoList");
	const photos = await (await fetch("/api/photos")).json();
	list.innerHTML = photos.map((p) => {
		const preview = p.mimeType && p.mimeType.startsWith("video/")
			? '<video src="/api/photos/' + p.id + '" muted></video>'
			: '<img src="/api/photos/' + p.id + '">';
		return '<span>' + preview + '<br><button onclick="deletePhoto(\\'' + p.id + '\\')">Delete</button></span>';
	}).join("");
}

async function uploadPhoto() {
	const file = document.getElementById("photoFile").files[0];
	if (!file) return;
	const form = new FormData();
	form.append("photo", file);
	await fetch("/api/photos", { method: "POST", body: form });
	loadPhotos();
}

async function deletePhoto(id) {
	await fetch("/api/photos/" + id, { method: "DELETE" });
	loadPhotos();
}

refresh();
</script>
</body>
</html>`;
