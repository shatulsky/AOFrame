# Coverage notes: AOFrame

The project part of the maintainer's `/improve-coverage` tool: this repo's test memory. Read it before adding tests; update it after each coverage run. This repo is public: no dates, no LAN addresses, no personal paths in this file. The guide for people is `docs/testing.md`.

## Measure

`bash .claude/coverage/measure.sh [BLOCK...]`, then read `coverage-report/summary.txt` and the floor table at the end.

Floor: 85 % lines, 75 % branches for every block with a coverage tool. No block has its own floor.

Time budget per block: hook 60 s, full run 300 s (the defaults). No block has its own budget.

Blocks:
- `app`: all three layers of the Android app (`scripts/run-all-tests.sh`: JVM unit, integration and E2E on the emulator `Frame`), floor on the merged JaCoCo report (`app/app/build/reports/jacoco/jacocoMergedReport/`). Full runs only (`HOOK_OFF`).
- `app-unit`: the JVM unit layer only (`gradlew :app:testDebugUnitTest`). Hook only; no floor, because the unit layer alone covers about 13 % of the app (the rest needs the Android runtime).
- `pi-video-gate`, `server`: Node, `c8 --all` over `node --test`. `c8` drops its default excludes when `--exclude` is given, so `measure.sh` lists them all (`C8_EXCLUDES`), plus `gate-fixture.js` and the runtime folders.
- `frame-scripts`: `node --test scripts/test/`, stub tests of the frame's shell scripts under bash. Bash has no coverage tool here: no floor, the tests must pass. About 10 s.

Facts that make it work:
- `JAVA_HOME` must point to a JDK the Android build accepts; `measure.sh` and `run-all-tests.sh` default to Android Studio's bundled JBR.
- The emulator AVD `Frame` (1080x1920, API 27, like the frame) must exist. `run-all-tests.sh` starts it headless when no device is attached (about a minute cold) and disconnects network ADB first.
- AGP 9 puts Kotlin classes in `intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`; a JaCoCo report with another class path is empty with no error.
- The guard audit finds no checks in Kotlin files; check a Kotlin guard by hand (switch it off, run the one test class, restore).
- The pre-commit hook (`.githooks/pre-commit`) runs the coverage step only when this file's `measure.sh` and the tool's `precommit.sh` exist; a plain clone skips it. `SKIP_COVERAGE=1 git commit ...` skips only that step.

## Parallel and serial

- `app`: the layers run one after the other. The integration and E2E layers share one emulator, one app data folder and port 8099, so they cannot run in parallel; inside a layer, instrumented tests run one at a time (the AndroidJUnitRunner default). The JVM unit tests have no shared state.
- `pi-video-gate`, `server`: Node runs the test files in parallel; every test has its own temp folder and a free port.
- `frame-scripts`: all tests run in parallel (`describe` with `concurrency: true`, async spawn): each has its own temp folder. Serial they took 33 s, because bash starts slowly on Windows; parallel about 10 s.
- `app` is in `HOOK_OFF`: it needs the emulator, and the hook would start one on every commit.

Measured times: full run about 45 s with the emulator running (app 39 s, Node blocks 1 to 3 s each, frame-scripts 10 s); hook run about 18 s with every block staged.

## Never run

- Never run the instrumented tests (integration, E2E) on the real frame: their setup deletes the app's stored settings and the app binds port 8099. `run-all-tests.sh` disconnects network ADB first; keep it that way.
- No test calls the real Immich, Open-Meteo, a real camera stream, ffmpeg, ffprobe, docker or a frame's control server. The live tests in `androidTest` (`*LiveTest`) are `@Ignore`d and run by hand only.
- The frame scripts are never run on a device by the tests: every device command is a shell function stub.

## Test patterns

- App unit (`app/src/test`): Humble Object. Logic moves out of `MainActivity` and `SlideshowRenderer` into plain classes (`Playlist`, `VideoTiming.watchdogAction`, `WeatherRefreshLoop`, `NightModeTiming`, ...); tests give them a fake delay or a fixed shuffle.
- App integration (`app/src/androidTest`, every package but `e2e`): MockWebServer for every HTTP client; a delay parameter with the production value as default where a test drives a retry (`ConfigBackupClient.fetchBackup(retryDelayMs)`).
- App E2E (`androidTest/.../e2e`): `FakeBackend` (one MockWebServer for the forecast, the local photo server and the video gate's webcam routes), `FrameConfig` (the app's config file), `FrameState.reset()`, `FramePage` (Page Object), `eventually` (poll with a deadline). Test media: `androidTest/assets/e2e-clip.mp4` (1 s, 96x64, made with ffmpeg's `testsrc`) and a 4x2 PNG inline. A new E2E test must fail when its feature is broken on purpose.
- pi-video-gate: `createGate(config, deps)` with `gate-fixture.js` (fake `execFile` for ffmpeg/ffprobe/nice/docker, fake `fetch` for Immich and the frame, temp state folder, fake clock, no retry delay). `whenIdle()` and `whenWebcamIdle()` replace waiting.
- server: `createApp(config, deps)` with `test/helpers.js` (a fake frame control server, a temp photo folder) and a fake `transcode`.
- frame scripts: the script is sourced with `WATCHDOG_SOURCE_ONLY=1` and `check_once` is called; `ping`, `dumpsys`, `settings`, `input`, `svc`, `su`, `sleep` and `date` are shell functions on a temp folder, and `sleep` moves a fake clock. The file paths come from environment variables that only the tests set.

## Good targets

Left on purpose, in ROI order:
- `MainActivity` (about 50 lines and 40 branches): the wake refresh and webcam-sync paths after a screen wake, the Immich (not local) sync path with its retries, sustained-performance mode. Next step: move the choice of the photo source (`syncAssets()`: local, Immich, missing config) into a plain class with unit tests.
- `SlideshowRenderer`: the stale-render guard (`generation != renderGeneration`), the preloaded back-player swap between two videos, the blur cache read-back. These need two videos in a row or exact timing; a renderer test with a fake player would be the way.
- `BootReceiver` (13 lines): it runs `su` and starts the activity on boot. Only a real boot shows it; not worth an emulator test.
- `ImmichClient` branch gaps (missing JSON fields, absolute download URLs): integration tests with MockWebServer, next to `ImmichClientTest`.
- `server.js` and `pi-video-gate/server.js` start-up blocks (`require.main`) and the real ffmpeg/ffprobe/docker calls: glue, not tested.

## Edge cases to try here

- Ids that are names of `Object` members (`__proto__`, `constructor`) in any lookup by id in a plain JS object: `server/routes/photos.js` wrote into `Object.prototype` through `PATCH /api/photos/__proto__/face`. Use `Object.hasOwn`.
- A request body that no handler reads, on a kept-alive connection (NanoHTTPD): the next request on that connection failed with 400. `LocalControlServer` now reads every body before routing.
- A guard flag set after an `await`: two triggers in that window both pass the check (webcam capture cycle in `pi-video-gate`).
- A temp file made inside a retried step must be removed on failure, too (`pi-video-gate` normalized video).
- A budget that is used up must not block the other checks of the same loop (the Wi-Fi watchdog's wrong-access-point restarts ran `continue` and hid a stall).

## Guard audit

`guard_audit.py find --any` on `pi-video-gate/server.js` and `server/routes/photos.js`, `server/lib/*.js`, then `run ... --test "cd <part> && node --test"`. Kotlin: by hand (see Measure).
- pi-video-gate: 39 checks, 29 killed at first, 1 timeout (`whenIdle`, a test helper: with it off the tests hang). Of 9 survivors, 4 got a test (the double webcam cycle, the ignored image with no error, the real ffprobe and docker errors). The 5 left end the same either way: without the check the code fails one step later into its own catch, or loops over an empty list (`lastSuccessEntry` null entry, a duration that is not a number, no frame URL, a non-OK frame status, no cameras).
- server: 10 checks, all killed (`ipWhitelist.js:37` checked by hand: the tool reported a compile error).
- `Playlist.kt`: the 3 empty-list guards, killed by hand.

## Free models

Edge-case lists from `/freeai`: allowed (public repo).

## Run log

- First full setup (commit after `66117b5`): app 80.8/63.9 → 92.4/78.5 (lines/branches, merged), pi-video-gate 2 helper modules only → 97.6/95.2, server none → 97.2/96.0, frame scripts none → 27 stub tests. Bugs fixed: prototype pollution in the photo manifest, NanoHTTPD leftover body breaking the next request, a second webcam cycle during the asleep check, normalized temp video left in `tmp/`, Wi-Fi stall check skipped after the wrong-access-point limit.
