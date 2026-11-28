/* LAN-only access control - this server (like the device's own
 * LocalControlServer it proxies) has no authentication, by design (see
 * the API reference doc's "Security posture" section). Enforcing this
 * at the network level via `config.json`'s `ipWhitelist` is the only
 * safeguard, so don't run this on anything but a trusted LAN, and don't
 * port-forward it to the internet.
 *
 * Deliberately minimal (exact match + IPv4 CIDR only) - the only shapes
 * a home LAN allowlist actually needs.
 */
const DEFAULT_ALLOWED = ["127.0.0.1", "::1"];

function ipToInt(ip) {
	return ip.split(".").reduce((acc, octet) => (acc << 8) + parseInt(octet, 10), 0) >>> 0;
}

function inCidr(ip, base, bits) {
	if (!/^\d+\.\d+\.\d+\.\d+$/.test(ip)) return false;
	const mask = bits === 0 ? 0 : (~0 << (32 - bits)) >>> 0;
	return (ipToInt(ip) & mask) === (ipToInt(base) & mask);
}

function isAllowed(ip, allowed) {
	return allowed.some((entry) => {
		if (entry.includes("/")) {
			const [base, bits] = entry.split("/");
			return inCidr(ip, base, parseInt(bits, 10));
		}
		return ip === entry;
	});
}

// createIpWhitelist(list) -> Express middleware; server.js passes config.ipWhitelist.
function createIpWhitelist(list) {
	const allowed = list || DEFAULT_ALLOWED;
	return function ipWhitelist(req, res, next) {
		if (isAllowed(req.ip, allowed)) {
			next();
			return;
		}
		res.status(403).send(`IP ${req.ip} is not allowed`);
	};
}

module.exports = { createIpWhitelist, isAllowed, inCidr, DEFAULT_ALLOWED };
