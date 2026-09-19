# App architecture notes

Why the app is built the way it is — useful if you're extending it, or
building something similar for the same class of hardware (a
single-purpose, always-on kiosk app on a RAM- and thermally-constrained
device).

## Plain Views, not Jetpack Compose

Compose was removed early. The UI here doesn't need reactive
state/recomposition — it's a handful of views (an `ImageView`, an
ExoPlayer surface, a couple of `ObjectAnimator`s) updated directly and
infrequently. On a device this RAM-constrained, there was no upside to
justify Compose's runtime cost for a UI this simple. This isn't a
blanket "Compose is slow" claim — it's specific to a UI shape that
doesn't benefit from Compose's actual strengths.

**Gotcha**: once Compose is removed, applying a separate
`org.jetbrains.kotlin.android`/`kotlin.compose` Gradle plugin can
actively break the build on a recent AGP — AGP versions from roughly 9.x
onward have built-in Kotlin support, and layering a standalone Kotlin
plugin on top conflicts with it rather than being a harmless no-op.

## ExoPlayer (Media3), not `VideoView`/`MediaPlayer`

Platform hardware video decoders can silently fall back to *software*
decode when a stream exceeds the device's declared hardware codec
ceiling — thermally disastrous on weak cores, and not something
`VideoView`'s higher-level API surfaces clearly. Media3 ExoPlayer with a
`SurfaceView` (not `TextureView` — the latter costs an extra composition
layer) gives enough visibility (via `AnalyticsListener`, see
[`performance-notes.md`](performance-notes.md)) to confirm which decoder
is actually running, plus the flexibility to normalize incoming video to
one confirmed-working profile server-side rather than hoping the device
handles arbitrary input gracefully.

## NanoHTTPD, not a heavier server framework

The on-device control server ([`local-control-server-api.md`](local-control-server-api.md))
only needs a handful of simple JSON endpoints. NanoHTTPD's narrow
dependency surface was chosen specifically to avoid pulling in a
full async engine (Netty, CIO, etc.) for a workload this small.

## Plain SQLite, not Room

The asset cache database is a `SQLiteOpenHelper` with two small tables.
Row count and schema complexity here don't justify Room's codegen and
dependency footprint for what's effectively a single-owner cache
manifest — a reasonable call for a project this size, though it's worth
reconsidering if the schema grows meaningfully more complex later.

## Scheduling: one in-process coordinator, not WorkManager/AlarmManager

Both were considered and rejected for this app's specific shape:

- **WorkManager** is built for guaranteed, possibly-deferred background
  work that should survive process death — overkill (and carries its own
  Room-backed job database) for work that only ever needs to run while
  the app is already alive.
- **AlarmManager** is built for exact-time wakeups when the *process*
  might be dead, and interacts poorly with Doze for anything recurring.

Since this is an always-on kiosk app (a visible fullscreen Activity, not
a background service), a single in-process coordinator (a coroutine/
`Handler`-based scheduler) covers everything needed — periodic sync,
clock/weather/countdown ticks — with one added discipline: **coalesce
wakeups through one coordinator** rather than several independent
timers, and stagger network/DB work away from slideshow transitions so a
sync doesn't visibly stutter the current photo/video.

## Process model: no foreground service needed

A persistent foreground service does **not**, by itself, protect an app
from the low-memory killer any more than a visible fullscreen Activity
already does — on Android, a visible foreground Activity already sits at
foreground process priority. Only reach for a foreground service if you
have work that must keep running *after* losing visibility (this app
doesn't).

## Control surface: local HTTP endpoints, no in-app settings UI

There's no settings screen in the app itself — every piece of runtime
config (night mode, countdown, slideshow timing, etc.) is exposed only
via the on-device HTTP API, consumed by whatever external admin tooling
you point at it (see the optional [reference server](../server/)).
**One deliberate exception**: a restart/recovery action is *not* exposed
as an in-app endpoint — a hung app process can't be trusted to answer
its own HTTP server to restart itself. That kind of action belongs at
the ADB/external-process level instead.

## Night mode: real display sleep, not just pausing the app

The scheduled sleep/wake window is implemented via a real display power
transition (`input keyevent KEYCODE_SLEEP`/`KEYCODE_WAKEUP` through root
shell) — **not** `KEYCODE_POWER`, which *toggles* power state and is
unreliable to drive from a schedule (you can't tell which state you're
about to toggle *into* without checking first, and a missed/duplicate
event flips the wrong way). Verify the real state via
`adb shell dumpsys power` (look for the actual wakefulness/display-power
fields), not just the exit code of the `input keyevent` command.

If your root grant is silent (some `su` implementations auto-grant root
to a specific app UID with no interactive prompt), confirm it's actually
working end-to-end rather than assuming — a command that reports success
but didn't really run as root fails in exactly the confusing way you'd
expect.

## Pausing rendering work when the display sleeps

Android's own Activity lifecycle (`onPause`/`onStop`/`onResume`) tracks
real display power state correctly for a single, always-foreground kiosk
app — but a `lifecycleScope`-launched coroutine only cancels on
`ON_DESTROY`, and a kiosk Activity that's never destroyed will keep any
coroutines launched in it running even while the screen is off. If you
have renderer/decoder/animator work that should genuinely pause during a
sleep window (not just become invisible), override `onPause`/`onResume`
explicitly rather than relying on lifecycle-scoped coroutine cancellation
alone.

## Running work exactly once per real wake

Sleep/wake isn't only driven by the schedule above — a manual action or
an external trigger (a home-automation integration, say) can put the
display to sleep or wake it at any time. Anything that should catch up
immediately after a real wake (this app's own example: forcing a fresh
webcam-clip capture rather than waiting for its own periodic cycle, so
arriving home doesn't show footage from whenever the screen fell
asleep) needs a "did we just wake up" signal, not a poll.

`onResume()` is that signal for free, per the previous section — for a
single foreground kiosk app it already correlates with the real display
turning back on, regardless of what caused it. The only thing missing
is debouncing: `onResume()` can also fire for reasons that aren't a
"new" wake (a brief resume/pause cycle, cold start immediately after
`onCreate()`), and repeating expensive work on every one of those would
be wasteful. `WakeRefreshGate` (`wake/WakeRefreshGate.kt`) is a small,
framework-agnostic debounce primitive for exactly this — plain Kotlin,
no Android dependency, injectable clock for tests, reusable for any
future "run this once per real wake" need beyond the one it currently
backs.

## A production-build (`org.json`) gotcha worth knowing

`JSONObject.optString(key, default)` returns the **literal string
`"null"`**, not your fallback, when the key's value is JSON `null`
(as opposed to the key being absent, which does return the fallback
correctly). Check `isNull(key)` explicitly first if a field might be
present-but-null rather than merely optional — this is easy to miss
since the two "missing" cases look identical until you hit real data
that distinguishes them.
