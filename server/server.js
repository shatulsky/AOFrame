/* AOFrame reference server - optional companion admin server. Proxies the
 * app's on-device LocalControlServer (night-mode/countdown/slideshow-settings/
 * status/etc.) for remote editing from a phone/laptop, and hosts the CRUD
 * photo API that backs the app's `photoSource: local` (Immich-optional) mode.
 *
 * Not required to run AOFrame at all - the app talks to Immich/OpenMeteo
 * directly and exposes its own LocalControlServer regardless of whether
 * this is running. This just makes remote editing/local-mode nicer than
 * curling the device's API by hand. See docs/local-control-server-api.md
 * for the full endpoint reference this proxies.
 *
 * LAN-only via lib/ipWhitelist.js - see that file and the setup docs for
 * why this has no authentication of its own.
 */
const express = require("express");
const ipWhitelist = require("./lib/ipWhitelist");
const registerDashboardRoutes = require("./routes/dashboard");
const registerFrameActionRoutes = require("./routes/frame-actions");
const registerPhotoRoutes = require("./routes/photos");

const config = require("./config.json");
const PORT = config.port || 8080;

const app = express();
app.use(ipWhitelist);

registerDashboardRoutes(app);
registerFrameActionRoutes(app);
registerPhotoRoutes(app);

app.listen(PORT, "0.0.0.0", () => {
	console.log(`AOFrame server listening on :${PORT}`);
});
