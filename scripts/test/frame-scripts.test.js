/* Stub tests for the frame's init scripts (wifi-watchdog.sh, cpu-governor.sh).
 * They run under bash, not the frame's mksh: the scripts use only POSIX sh
 * plus $(( 10#.. )), which both shells have. Nothing here touches a device:
 * ping, dumpsys, settings, input, svc, su, sleep and date are shell functions
 * that read and write a temp folder ($SB) and log every call to $SB/calls.
 * sleep does not wait: it moves a fake clock ($SB/now) forward.
 */
const { test, describe } = require("node:test");
const assert = require("node:assert/strict");
const { spawn, spawnSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

const SCRIPTS = path.join(__dirname, "..");
const WATCHDOG = path.join(SCRIPTS, "wifi-watchdog", "wifi-watchdog.sh");
const GOVERNOR = path.join(SCRIPTS, "cpu-governor", "cpu-governor.sh");

// Git Bash on Windows (System32's bash is WSL and may have no distro), else bash on PATH.
function findBash() {
	for (const p of ["C:/Program Files/Git/bin/bash.exe", "bash"]) {
		const r = spawnSync(p, ["-c", "echo ok"], { encoding: "utf8" });
		if (r.status === 0 && r.stdout.trim() === "ok") return p;
	}
	return null;
}
const BASH = findBash();
const opts = { skip: BASH ? false : "no working bash" };
// run(): spawnSync's result shape, without blocking, so the tests can overlap.
function run(cmd, args, options) {
	return new Promise((resolve) => {
		const child = spawn(cmd, args, options);
		let stdout = "";
		let stderr = "";
		child.stdout.on("data", (d) => { stdout += d; });
		child.stderr.on("data", (d) => { stderr += d; });
		child.on("close", (status) => resolve({ status, stdout, stderr }));
	});
}
const unix = (p) => p.replace(/\\/g, "/");

const STUBS = String.raw`
call() { echo "$*" >> "$SB/calls"; }
now() { cat "$SB/now"; }
sleep() { call sleep "$1"; echo $(( $(now) + $1 )) > "$SB/now"; }
date() {
    case "$1" in
        +%s) now ;;
        +%H:%M) printf '%02d:%02d\n' $(( $(now) / 3600 % 24 )) $(( $(now) / 60 % 60 )) ;;
        *) echo stamp ;;
    esac
}
ping() { [ "$(cat "$SB/gw")" = up ]; }
dumpsys() {
    case "$1" in
        power) echo "  mWakefulness=$(cat "$SB/wake")" ;;
        wifi) b=$(cat "$SB/bssid"); [ -n "$b" ] && echo "mWifiInfo SSID: home, BSSID: $b, MAC: x" ;;
    esac
}
settings() {
    call settings "$@"
    case "$1 $2 $3" in
        "get global wifi_on") cat "$SB/wifi_on" ;;
        "get system screen_brightness") cat "$SB/brightness" ;;
        "put system screen_brightness") echo "$4" > "$SB/brightness" ;;
    esac
}
input() {
    call input "$@"
    case "$2" in
        KEYCODE_WAKEUP) echo Awake > "$SB/wake"; [ -n "$GW_ON_WAKE" ] && echo "$GW_ON_WAKE" > "$SB/gw"; echo 120 > "$SB/backlight" ;;
        KEYCODE_SLEEP) echo Asleep > "$SB/wake" ;;
    esac
}
svc() {
    call svc "$@"
    [ "$2" = enable ] && [ -n "$GW_ON_RESTART" ] && echo "$GW_ON_RESTART" > "$SB/gw"
    [ "$2" = enable ] && [ -n "$BSSID_ON_RESTART" ] && echo "$BSSID_ON_RESTART" > "$SB/bssid"
    true
}
`;

/* Fixture object: a temp folder with the fake device state.
 * state: { gw, wake, bssid, wifi_on, brightness, now (s since midnight), files: {name: text} } */
function frame(state = {}) {
	const dir = fs.mkdtempSync(path.join(os.tmpdir(), "frame-scripts-"));
	const s = { gw: "up", wake: "Awake", bssid: "aa:aa:aa:aa:aa:01", wifi_on: "1", brightness: "80", backlight: "0", now: 12 * 3600, ...state };
	for (const key of ["gw", "wake", "bssid", "wifi_on", "brightness", "backlight", "now"]) fs.writeFileSync(path.join(dir, key), `${s[key]}\n`);
	fs.writeFileSync(path.join(dir, "calls"), "");
	for (const [name, text] of Object.entries(state.files || {})) fs.writeFileSync(path.join(dir, name), text);
	return {
		dir,
		read: (name) => (fs.existsSync(path.join(dir, name)) ? fs.readFileSync(path.join(dir, name), "utf8") : null),
		write: (name, text) => fs.writeFileSync(path.join(dir, name), text),
		calls() { return this.read("calls").trim().split("\n").filter(Boolean); },
		log() { return (this.read("watchdog.log") || "").trim().split("\n").filter(Boolean).map((l) => l.replace(/^stamp /, "")); },
		done: () => fs.rmSync(dir, { recursive: true, force: true }),
	};
}

/* Runs `body` (bash) after sourcing the watchdog with the stubs and the temp paths.
 * vars: shell variables set after sourcing (they replace the script's settings). */
async function watchdog(f, body, { vars = {}, env = {} } = {}) {
	const sb = unix(f.dir);
	const setVars = Object.entries(vars).map(([k, v]) => `${k}='${v}'`).join("\n");
	const program = [
		STUBS,
		`WATCHDOG_SOURCE_ONLY=1 . "${unix(WATCHDOG)}"`,
		setVars,
		"fails=0; awake_fails=0; last_bssid=$(current_bssid)",
		body,
	].join("\n");
	const r = await run(BASH, ["-c", program], {
		env: {
			...process.env,
			...env,
			SB: sb,
			NIGHT_MODE_FILE: `${sb}/night-mode.json`,
			BACKLIGHT: `${sb}/backlight`,
			STATE: `${sb}/brightness.saved`,
			LOG: `${sb}/watchdog.log`,
			RESTARTS: `${sb}/restarts`,
			WRONG_UNIT_RESTARTS: `${sb}/wrongunit`,
		},
	});
	assert.equal(r.status, 0, r.stderr);
	return r;
}

describe("frame scripts", { concurrency: true }, () => {
const checks = (n) => `for n in $(seq ${n}); do check_once; done; echo "fails=$fails awake=$awake_fails last=$last_bssid"`;

test("watchdog: gateway up, nothing happens and the counters stay 0", opts, async (t) => {
	const f = frame();
	t.after(f.done);
	const r = await watchdog(f, checks(5));
	assert.match(r.stdout, /fails=0 awake=0/);
	assert.deepEqual(f.calls().filter((c) => !c.startsWith("settings get")), []);
});

test("watchdog: Wi-Fi switched off by the user is never a stall", opts, async (t) => {
	const f = frame({ gw: "down", wifi_on: "0" });
	t.after(f.done);
	const r = await watchdog(f, checks(10));
	assert.match(r.stdout, /fails=0 awake=0/);
	assert.ok(!f.calls().some((c) => c.startsWith("svc") || c.startsWith("input")));
});

test("watchdog: screen awake, a stall of 180 s restarts Wi-Fi once and the gateway comes back", opts, async (t) => {
	const f = frame({ gw: "down" });
	t.after(f.done);
	await watchdog(f, checks(5), { env: { GW_ON_RESTART: "up" } });
	assert.ok(!f.calls().includes("svc wifi disable"), "5 x 30 s is under 180 s");
	await watchdog(f, checks(6), { env: { GW_ON_RESTART: "up" } });
	assert.deepEqual(f.calls().filter((c) => c.startsWith("svc")), ["svc wifi disable", "svc wifi enable"]);
	assert.match(f.log().join("\n"), /wifi stall for 180s, screen awake: svc wifi disable\/enable \(BSSID before: aa:aa:aa:aa:aa:01\)/);
	assert.match(f.log().join("\n"), /wifi restart: gateway reachable after 0s/);
});

test("watchdog: a restart that does not help logs it after the wait", opts, async (t) => {
	const f = frame({ gw: "down" });
	t.after(f.done);
	await watchdog(f, checks(6));
	assert.match(f.log().join("\n"), /wifi restart: gateway still unreachable after 30s \(BSSID now: aa:aa:aa:aa:aa:01\)/);
});

test("watchdog: at most 3 stall restarts per hour; older ones fall out of the window", opts, async (t) => {
	const f = frame({ gw: "down" });
	t.after(f.done);
	await watchdog(f, checks(6 * 4));
	assert.equal(f.calls().filter((c) => c === "svc wifi disable").length, 3);
	assert.match(f.log().join("\n"), /restart limit reached \(3 in the last hour\): no action/);
	f.write("now", `${Number(f.read("now")) + 3600}\n`);
	await watchdog(f, checks(6));
	assert.equal(f.calls().filter((c) => c === "svc wifi disable").length, 4);
});

test("watchdog: screen asleep, 2 failed checks make a dark wake that ends asleep with the brightness back", opts, async (t) => {
	const f = frame({ gw: "down", wake: "Asleep" });
	t.after(f.done);
	const r = await watchdog(f, `check_once; echo "after one: $(grep -c input "$SB/calls")" >&2; check_once`, { env: { GW_ON_WAKE: "up" } });
	assert.match(r.stderr, /after one: 0/, "one failed check is not enough");
	const calls = f.calls();
	assert.deepEqual(calls.filter((c) => c.startsWith("input")), ["input keyevent KEYCODE_WAKEUP", "input keyevent KEYCODE_SLEEP"]);
	assert.ok(calls.includes("settings put system screen_brightness 1"));
	assert.equal(calls.at(-2), "settings put system screen_brightness 80");
	assert.equal(calls.at(-1), "sleep 270", "cool-down after a dark wake");
	assert.equal(f.read("backlight").trim(), "0", "backlight forced off during the wake");
	assert.equal(f.read("wake").trim(), "Asleep");
	assert.equal(f.read("brightness.saved"), null);
	assert.match(f.log().join("\n"), /dark wake: gateway reachable after 0s/);
});

test("watchdog: a dark wake that finds no gateway still goes back to sleep", opts, async (t) => {
	const f = frame({ gw: "down", wake: "Asleep" });
	t.after(f.done);
	await watchdog(f, checks(2));
	assert.match(f.log().join("\n"), /dark wake: gateway still unreachable after 30s/);
	assert.equal(f.read("wake").trim(), "Asleep");
	assert.equal(f.read("brightness").trim(), "80");
});

test("watchdog: if someone put the screen to sleep during the dark wake, no second sleep key is sent", opts, async (t) => {
	const f = frame({ gw: "down", wake: "Asleep" });
	t.after(f.done);
	// The gateway never answers; after the 5th ping the "user" turns the screen off.
	await watchdog(f, `ping() { n=$(( $(cat "$SB/pings" 2>/dev/null || echo 0) + 1 )); echo $n > "$SB/pings"; [ $n = 7 ] && echo Asleep > "$SB/wake"; false; }\n${checks(2)}`);
	assert.ok(!f.calls().includes("input keyevent KEYCODE_SLEEP"));
});

const WAKE_CASES = [
	["07:30", 7 * 3600 + 27 * 60, false],
	["07:30", 7 * 3600 + 32 * 60, false],
	["07:30", 7 * 3600 + 26 * 60, true],
	["07:30", 7 * 3600 + 33 * 60, true],
	["00:01", 23 * 3600 + 59 * 60, false],
	["23:59", 0 * 3600 + 1 * 60, false],
	["08:05", 20 * 3600, true],
];
for (const [wake, now, expectWake] of WAKE_CASES) {
	const at = `${String(Math.floor(now / 3600)).padStart(2, "0")}:${String((now / 60) % 60).padStart(2, "0")}`;
	test(`watchdog: wake time ${wake}, now ${at}: dark wake ${expectWake ? "runs" : "is skipped (within -3..+2 min)"}`, opts, async (t) => {
		const f = frame({ gw: "down", wake: "Asleep", now, files: { "night-mode.json": `{"enabled":true,"sleepTime":"23:00","wakeTime":"${wake}"}` } });
		t.after(f.done);
		await watchdog(f, checks(2));
		assert.equal(f.calls().includes("input keyevent KEYCODE_WAKEUP"), expectWake);
		if (!expectWake) assert.match(f.log().join("\n"), /scheduled wake is near: no action/);
	});
}

test("watchdog: without a night-mode file the dark wake always runs", opts, async (t) => {
	const f = frame({ gw: "down", wake: "Asleep" });
	t.after(f.done);
	await watchdog(f, checks(2));
	assert.ok(f.calls().includes("input keyevent KEYCODE_WAKEUP"));
});

test("watchdog: a roam is logged, and a lost connection (no BSSID) is not a roam", opts, async (t) => {
	const f = frame();
	t.after(f.done);
	const r = await watchdog(f, `check_once; echo > "$SB/bssid"; check_once; echo "bb:bb:bb:bb:bb:02" > "$SB/bssid"; check_once; echo "last=$last_bssid"`);
	assert.deepEqual(f.log(), ["roam: BSSID aa:aa:aa:aa:aa:01 -> bb:bb:bb:bb:bb:02"]);
	assert.match(r.stdout, /last=bb:bb:bb:bb:bb:02/);
});

test("watchdog: on the wrong access point with the screen awake, Wi-Fi restarts (own limit: 2 per hour)", opts, async (t) => {
	const f = frame({ bssid: "bb:bb:bb:bb:bb:02" });
	t.after(f.done);
	await watchdog(f, checks(4), { vars: { PREFERRED_BSSID: "aa:aa:aa:aa:aa:01" } });
	assert.equal(f.calls().filter((c) => c === "svc wifi disable").length, 2);
	assert.match(f.log().join("\n"), /frame on wrong unit \(bb:bb:bb:bb:bb:02\), screen awake/);
	assert.equal(f.read("restarts"), null, "the stall budget is not used");
});

test("watchdog: a restart that reaches the preferred access point stops the restarts", opts, async (t) => {
	const f = frame({ bssid: "bb:bb:bb:bb:bb:02" });
	t.after(f.done);
	const r = await watchdog(f, checks(3), { vars: { PREFERRED_BSSID: "aa:aa:aa:aa:aa:01" }, env: { BSSID_ON_RESTART: "aa:aa:aa:aa:aa:01" } });
	assert.equal(f.calls().filter((c) => c === "svc wifi disable").length, 1);
	assert.match(r.stdout, /last=aa:aa:aa:aa:aa:01/);
});

test("watchdog: a BSSID of all zeros (no link) is no access point: no wrong-unit restart, no roam", opts, async (t) => {
	const f = frame({ bssid: "00:00:00:00:00:00" });
	t.after(f.done);
	const r = await watchdog(f, `${checks(3)}; echo "now=$(current_bssid)"`, { vars: { PREFERRED_BSSID: "aa:aa:aa:aa:aa:01" } });
	assert.ok(!f.calls().includes("svc wifi disable"));
	assert.match(r.stdout, /now=none/);
	assert.deepEqual(f.log(), []);
});

test("watchdog: on the wrong access point, no restart while the screen sleeps or Wi-Fi is off", opts, async (t) => {
	await Promise.all([{ wake: "Asleep" }, { wifi_on: "0" }].map(async (state) => {
		const f = frame({ bssid: "bb:bb:bb:bb:bb:02", ...state });
		t.after(f.done);
		await watchdog(f, checks(3), { vars: { PREFERRED_BSSID: "aa:aa:aa:aa:aa:01" } });
		assert.ok(!f.calls().includes("svc wifi disable"), JSON.stringify(state));
	}));
});

test("watchdog: on the wrong access point with its limit used up, a gateway stall still restarts Wi-Fi", opts, async (t) => {
	const f = frame({ bssid: "bb:bb:bb:bb:bb:02", gw: "down" });
	t.after(f.done);
	const now = 12 * 3600;
	f.write("wrongunit", ` ${now - 100} ${now - 50}\n`);
	await watchdog(f, checks(6), { vars: { PREFERRED_BSSID: "aa:aa:aa:aa:aa:01" } });
	assert.match(f.log().join("\n"), /frame on wrong unit .* restart limit reached/);
	assert.equal(f.calls().filter((c) => c === "svc wifi disable").length, 1);
	assert.match(f.log().join("\n"), /wifi stall for 180s/);
});

test("watchdog: restore_brightness undoes an unfinished dark wake, and does nothing otherwise", opts, async (t) => {
	const f = frame({ brightness: "1", files: { "brightness.saved": "95\n" } });
	t.after(f.done);
	await watchdog(f, "restore_brightness; restore_brightness");
	assert.equal(f.read("brightness").trim(), "95");
	assert.equal(f.read("brightness.saved"), null);
	assert.equal(f.calls().filter((c) => c.startsWith("settings put")).length, 1);
});

test("watchdog: the log starts again at 64 KB and keeps the old one", opts, async (t) => {
	const f = frame({ files: { "watchdog.log": "x".repeat(65537) } });
	t.after(f.done);
	await watchdog(f, `log "hello"`);
	assert.deepEqual(f.log(), ["hello"]);
	assert.equal(f.read("watchdog.log.old").length, 65537);
});

test("watchdog: run as the service, it starts, restores the brightness and checks every 30 s", opts, async (t) => {
	const f = frame({ files: { "brightness.saved": "70\n" } });
	t.after(f.done);
	// The loop never ends by itself: the 3rd sleep after start stops the run.
	const stop = `sleep() { call sleep "$1"; n=$(( $(cat "$SB/sleeps" 2>/dev/null || echo 0) + 1 )); echo $n > "$SB/sleeps"; [ $n -ge 4 ] && exit 0; true; }`;
	const sb = unix(f.dir);
	const r = await run(BASH, ["-c", `${STUBS}\n${stop}\n. "${unix(WATCHDOG)}"`], { env: { ...process.env, SB: sb, LOG: `${sb}/watchdog.log`, STATE: `${sb}/brightness.saved`, BACKLIGHT: `${sb}/backlight`, NIGHT_MODE_FILE: `${sb}/nm.json`, RESTARTS: `${sb}/r`, WRONG_UNIT_RESTARTS: `${sb}/w` },
	});
	assert.equal(r.status, 0, r.stderr);
	assert.deepEqual(f.calls().filter((c) => c.startsWith("sleep")), ["sleep 120", "sleep 30", "sleep 30", "sleep 30"]);
	assert.deepEqual(f.log().slice(0, 2), ["started", `restored screen_brightness from ${sb}/brightness.saved`]);
	assert.equal(f.read("brightness").trim(), "70");
});

// ------------------------------------------------------------ cpu-governor.sh

/* Runs cpu-governor.sh with a fake sysfs. su writes the governor only from
 * attempt `readyAt` on (the SuperSU daemon is not ready before). */
async function governor(t, { readyAt = 1 } = {}) {
	const f = frame();
	t.after(f.done);
	for (const c of [0, 1, 2, 3]) {
		fs.mkdirSync(path.join(f.dir, "cpu", `cpu${c}`, "cpufreq"), { recursive: true });
		fs.writeFileSync(path.join(f.dir, "cpu", `cpu${c}`, "cpufreq", "scaling_governor"), "interactive\n");
	}
	const su = String.raw`su() {
    call su "$@"
    [ "$1" = --daemon ] && return 0
    n=$(( $(cat "$SB/su-calls" 2>/dev/null || echo 0) + 1 )); echo $n > "$SB/su-calls"
    [ $(( (n + 3) / 4 )) -ge ${readyAt} ] || { echo "su: daemon not ready"; return 1; }
    sh -c "$2"
}`;
	const sb = unix(f.dir);
	const r = await run(BASH, ["-c", `${STUBS}\n${su}\n. "${unix(GOVERNOR)}"`], { env: { ...process.env, SB: sb, CPU_ROOT: `${sb}/cpu`, LOG: `${sb}/governor.log` },
	});
	assert.equal(r.status, 0, r.stderr);
	const govs = [0, 1, 2, 3].map((c) => f.read(`cpu/cpu${c}/cpufreq/scaling_governor`).trim());
	return { f, govs, log: f.read("governor.log"), sleeps: f.calls().filter((c) => c.startsWith("sleep")).length };
}

test("governor: all 4 cores get conservative on the first attempt", opts, async (t) => {
	const { govs, log, sleeps } = await governor(t);
	assert.deepEqual(govs, ["conservative", "conservative", "conservative", "conservative"]);
	assert.match(log, /GOVERNOR_ACTIVE/);
	assert.equal(sleeps, 0);
});

test("governor: retries each second until su is ready", opts, async (t) => {
	const { govs, log, sleeps } = await governor(t, { readyAt: 4 });
	assert.deepEqual(new Set(govs), new Set(["conservative"]));
	assert.equal(sleeps, 3);
	assert.equal(log.match(/daemon not ready/g).length, 12);
	assert.match(log, /GOVERNOR_ACTIVE/);
});

test("governor: gives up after 10 attempts without GOVERNOR_ACTIVE", opts, async (t) => {
	const { govs, log, sleeps } = await governor(t, { readyAt: 99 });
	assert.deepEqual(new Set(govs), new Set(["interactive"]));
	assert.equal(sleeps, 10);
	assert.doesNotMatch(log, /GOVERNOR_ACTIVE/);
});
});
