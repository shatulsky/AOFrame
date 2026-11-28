const test = require("node:test");
const assert = require("node:assert/strict");
const { createIpWhitelist, isAllowed, inCidr, DEFAULT_ALLOWED } = require("../lib/ipWhitelist");

test("inCidr matches the network part only", () => {
	assert.equal(inCidr("192.168.0.42", "192.168.0.0", 24), true);
	assert.equal(inCidr("192.168.1.42", "192.168.0.0", 24), false);
	assert.equal(inCidr("198.51.100.7", "198.0.0.0", 8), true);
	assert.equal(inCidr("199.51.100.7", "198.0.0.0", 8), false);
});

test("inCidr /0 allows every IPv4 and /32 only the exact address", () => {
	assert.equal(inCidr("8.8.8.8", "0.0.0.0", 0), true);
	assert.equal(inCidr("192.168.0.5", "192.168.0.5", 32), true);
	assert.equal(inCidr("192.168.0.6", "192.168.0.5", 32), false);
});

test("inCidr never matches a non-IPv4 address (IPv6, mapped IPv6, empty)", () => {
	assert.equal(inCidr("::1", "0.0.0.0", 0), false);
	assert.equal(inCidr("::ffff:192.168.0.5", "192.168.0.0", 24), false);
	assert.equal(inCidr("", "0.0.0.0", 0), false);
});

test("isAllowed takes exact entries and CIDR entries from one list", () => {
	const list = ["::1", "192.168.0.0/24"];
	assert.equal(isAllowed("::1", list), true);
	assert.equal(isAllowed("192.168.0.9", list), true);
	assert.equal(isAllowed("192.168.1.9", list), false);
	assert.equal(isAllowed("127.0.0.1", list), false);
});

test("an exact entry is not a prefix match", () => {
	assert.equal(isAllowed("127.0.0.10", ["127.0.0.1"]), false);
});

function call(middleware, ip) {
	const out = { next: false, status: null, body: null };
	const res = { status(s) { out.status = s; return this; }, send(b) { out.body = b; return this; } };
	middleware({ ip }, res, () => { out.next = true; });
	return out;
}

test("the middleware passes an allowed IP and answers 403 to any other", () => {
	const mw = createIpWhitelist(["192.168.0.0/24"]);
	assert.deepEqual(call(mw, "192.168.0.3"), { next: true, status: null, body: null });
	const denied = call(mw, "203.0.113.7");
	assert.equal(denied.next, false);
	assert.equal(denied.status, 403);
	assert.match(denied.body, /203\.0\.113\.7/);
});

test("without a configured list only loopback is allowed", () => {
	assert.deepEqual(DEFAULT_ALLOWED, ["127.0.0.1", "::1"]);
	const mw = createIpWhitelist(undefined);
	assert.equal(call(mw, "127.0.0.1").next, true);
	assert.equal(call(mw, "192.168.0.3").next, false);
});
