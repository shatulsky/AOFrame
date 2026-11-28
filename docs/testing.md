# Testing

The Android app has three test layers. The two Node services and the frame's shell scripts have their own tests (see "Other parts" below). Each check sits in the lowest layer that can see the defect. The rules are the same as in the author's other projects: a test pyramid, named patterns (Humble Object, Page Object, fake server), no fixed sleeps, no retries.

| Layer | Where | Runs on | What belongs here |
|---|---|---|---|
| Unit | `app/src/test` | plain JVM, about 4 s | Pure logic: timing, formatting, retry and backoff, display rules, night-mode times, wake debounce. A fake clock and a fake delay replace the real ones. |
| Integration | `app/src/androidTest` (every package except `e2e`) | emulator, about 10 s | Code that needs the Android runtime: SQLite, `org.json`, the control server on port 8099, the Immich, local photo, webcam and Open-Meteo clients against a MockWebServer. |
| UI (E2E) | `app/src/androidTest/.../e2e` | emulator, about 12 s | Only what a screen shows: the loading screen goes away, a photo is on screen, the clock, the weather widget (hidden until loaded, shown after a retry, hidden without coordinates), the live view button. One smoke per workflow. |

## Run

Use the emulator `Frame` (1080x1920, API 27, like the frame). **Never run the instrumented tests on the real frame.** Their setup deletes the app's settings and the app binds port 8099.

```
JAVA_HOME="<Android Studio>/jbr" scripts/run-all-tests.sh            # all layers
scripts/run-all-tests.sh unit                                         # one layer
scripts/run-all-tests.sh integration e2e
```

The script starts the emulator headless when none is attached, disconnects network ADB first, runs each layer and prints the time and the coverage of each layer and of all together. One class only: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<class>`.

## How the layers are built

- **Humble Object.** Logic that was in `MainActivity` and `SlideshowRenderer` is in plain classes: `WeatherRefreshLoop` (retry after 1 minute, doubling, capped at the normal interval), `WeatherDisplayRules` (day hours, widget visible only with 3 forecast days), `retryUpTo` (slideshow sync attempts), `NightModeTiming`, `WakeRefreshGate`, `Playlist` (rotation order: shuffle, keep the current slide on a list update, wrap and reshuffle), `VideoTiming.watchdogAction` (advance, preload or wait after each poll of the player). The activity and the renderer only call them. Tests give them a fake delay or a fixed shuffle, so no test waits.
- **Fake backend.** `e2e/FrameHarness.kt` has `FakeBackend`: one MockWebServer that answers the forecast, the marine forecast, the local photo API and the webcam routes of the video gate. The video test uses `androidTest/assets/e2e-clip.mp4` (1 s, 96x64, made with ffmpeg's `testsrc`). The app is pointed at it through `immich-secrets.json` (`weatherBaseUrl`, `weatherMarineBaseUrl`, `weatherRetryInitialSeconds`, `photoSource: local`). `FrameConfig` builds that file and `FrameState.reset()` clears every setting before a scenario.
- **Page Object.** `e2e/FramePage.kt` is the screen. Tests read `page.weatherWidgetShown`, never a view id.
- **Waiting.** `eventually(what) { condition }` polls with a deadline and returns as soon as the condition holds. There is no fixed sleep and no retry of a failed test.
- **Test ids.** The layout view ids are the test ids. Add an id to a view before you test it.

## Rules

- A check that logic can answer goes to the unit layer. Do not add an E2E test for a rule that a unit test can see: with the widget rule broken, no E2E test fails by design, and the unit test does.
- A new E2E test must fail when the feature breaks. Break the feature on purpose once and see the test fail.
- No test uses the real Immich, Open-Meteo or a real camera. A live check is `@Ignore`d and run by hand.
- Do not write dates in code comments or docs of this public repo.

## Coverage

`scripts/run-all-tests.sh` collects JaCoCo data per layer (debug build) and `./gradlew :app:jacocoMergedReport` writes the merged report (`-Player=unit|integration|e2e` for one layer) to `app/build/reports/jacoco/jacocoMergedReport/`. Merged coverage is about 92 % of lines and 78 % of branches. The gaps that stay, and why, are in `.claude/coverage/notes.md` ("Good targets"). New logic should come with a unit test first.

The maintainer's coverage tool runs every part with one script, `.claude/coverage/measure.sh`, and fails a part under 85 % of lines or 75 % of branches. The pre-commit hook runs it for the staged parts when the tool is installed; a plain clone skips that step.

## Other parts

| Part | Tests | Run | What the fakes replace |
|---|---|---|---|
| `pi-video-gate/` | `gate-*.test.js`, `redact.test.js`, `frozen-stream.test.js` | `cd pi-video-gate && node --test` | `createGate(config, deps)` takes a fake `execFile` (ffmpeg, ffprobe, nice, docker), a fake `fetch` (Immich, the frame), a temp state folder and a fake clock. See `gate-fixture.js`. |
| `server/` | `test/*.test.js` | `cd server && node --test` | `createApp(config, deps)` runs in-process against a fake frame control server, a temp photo folder and a fake `transcode` (no ffmpeg). |
| `scripts/` (frame shell scripts) | `test/frame-scripts.test.js` | `cd scripts && node --test test/` | The scripts run under bash; `ping`, `dumpsys`, `settings`, `input`, `svc`, `su`, `sleep` and `date` are shell functions on a temp folder. `wifi-watchdog.sh` is sourced with `WATCHDOG_SOURCE_ONLY=1` and the test calls `check_once`. Bash is not the frame's mksh: the scripts use only POSIX sh plus `$(( 10#.. ))`, which both have. |

None of these tests runs ffmpeg, docker or a real camera, or touches Immich or a frame.

## Time and parallelism

Unit about 5 s, integration about 10 s, E2E about 18 s, about 40 s for all three with the emulator running. The Node tests take 1 to 3 s per part and the shell script tests about 10 s; they run in parallel (each test has its own temp folder and port). The layers run one after the other: the instrumented layers share one emulator and one app data folder and bind port 8099, so they cannot run in parallel. Unit tests have no shared state and run with Gradle's default parallelism. Starting the emulator cold adds about a minute.
