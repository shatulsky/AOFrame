# Testing

AOFrame has three test layers. Each check sits in the lowest layer that can see the defect. The rules are the same as in the author's other projects: a test pyramid, named patterns (Humble Object, Page Object, fake server), no fixed sleeps, no retries.

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

- **Humble Object.** Logic that was in `MainActivity` is in plain classes: `WeatherRefreshLoop` (retry after 1 minute, doubling, capped at the normal interval), `WeatherDisplayRules` (day hours, widget visible only with 3 forecast days), `retryUpTo` (slideshow sync attempts), `NightModeTiming`, `WakeRefreshGate`. The activity only calls them. Tests give them a fake delay, so no test waits.
- **Fake backend.** `e2e/FrameHarness.kt` has `FakeBackend`: one MockWebServer that answers the forecast, the marine forecast and the local photo API. The app is pointed at it through `immich-secrets.json` (`weatherBaseUrl`, `weatherMarineBaseUrl`, `weatherRetryInitialSeconds`, `photoSource: local`). `FrameConfig` builds that file and `FrameState.reset()` clears every setting before a scenario.
- **Page Object.** `e2e/FramePage.kt` is the screen. Tests read `page.weatherWidgetShown`, never a view id.
- **Waiting.** `eventually(what) { condition }` polls with a deadline and returns as soon as the condition holds. There is no fixed sleep and no retry of a failed test.
- **Test ids.** The layout view ids are the test ids. Add an id to a view before you test it.

## Rules

- A check that logic can answer goes to the unit layer. Do not add an E2E test for a rule that a unit test can see: with the widget rule broken, no E2E test fails by design, and the unit test does.
- A new E2E test must fail when the feature breaks. Break the feature on purpose once and see the test fail.
- No test uses the real Immich, Open-Meteo or a real camera. A live check is `@Ignore`d and run by hand.
- Do not write dates in code comments or docs of this public repo.

## Coverage

`scripts/run-all-tests.sh` collects JaCoCo data per layer (debug build) and `./gradlew :app:jacocoMergedReport` writes the merged report (`-Player=unit|integration|e2e` for one layer) to `app/build/reports/jacoco/jacocoMergedReport/`. The biggest gaps are `SlideshowRenderer` (animation and decode paths), `MainActivity` (webcam-only mode, wake refresh), `BootReceiver` and the `restoreFromPiIfMissing` paths. New logic should come with a unit test first.

## Time and parallelism

Unit about 4 s, integration about 10 s, E2E about 12 s, 31 s for all three with the emulator running. The layers run one after the other: the instrumented layers share one emulator and one app data folder and bind port 8099, so they cannot run in parallel. Unit tests have no shared state and run with Gradle's default parallelism. Starting the emulator cold adds about a minute.
