# Performance notes

Concrete findings from tuning AOFrame to run smoothly on the low-end,
passively-cooled Rockchip hardware these frames commonly ship with.
Numbers below are from the reference hardware and won't transfer exactly
to your device, but the underlying lessons generally will — most of this
class of hardware shares the same constraints (thermal throttling in an
enclosed case, ~1GB RAM, an old but real hardware video decoder).

## Confirm hardware video decode is actually being used

Don't assume — verify. A `Media3`/ExoPlayer `AnalyticsListener` hooked to
`onVideoDecoderInitialized`/`onDroppedVideoFrames` confirmed the real
decoder component in use:

```
video decoder=OMX.rk.video_decoder.avc initDurationMs=320
```

A name like `OMX.rk.video_decoder.avc` (vendor-prefixed, not
`OMX.google.*`) is a genuine hardware decoder component, not a software
fallback — worth confirming this way rather than inferring it from CPU
usage alone, since a software decoder under moderate load can look
deceptively similar at a glance. Keeping a lightweight version of this
listener as a permanent diagnostic (one log line per decoder
init/dropped-frame event, not a per-frame cost) is cheap insurance
against a future update silently falling back to software decode.

**A device limitation discovered against one client doesn't necessarily
transfer to a different client on the same hardware.** A resolution/level
ceiling that seemed to matter for a browser-based video pipeline turned
out not to be a real hardware ceiling at all once measured directly
against this app's own `MediaCodec` selection — check `/vendor/etc/media_codecs.xml`
(or query `MediaCodecList` at runtime) for your device's actual declared
capability, and re-verify after any change to how video reaches the
device, rather than reusing an old conclusion across different playback
paths.

## Bitmap decode format: `RGB_565` for opaque photo thumbnails

Switching the main on-screen photo decode from the `BitmapFactory`
default (`ARGB_8888`, 4 bytes/pixel) to `RGB_565` (2 bytes/pixel) halves
the memory and upload cost of the one bitmap held at a time at full panel
resolution, with **no visible quality loss for opaque photo thumbnails**
(no alpha channel to lose either way — this doesn't apply if you need
transparency). Measured effect: the frame-time *tail* improved noticeably
(95th/99th percentile), since the occasional slow frame — a bitmap upload
landing on an already-busy frame — got cheaper. A blurred backdrop layer
that needs `ARGB_8888` for its blur intrinsic (`RenderScript`'s
`ScriptIntrinsicBlur` requires `Element.U8_4`) is unaffected by this and
can stay at the higher format.

## Ken Burns / continuous animation: verify your fps cap actually caps anything

`ValueAnimator.setFrameDelay()`'s own API documentation says the
requested delay "may be ignored when the animation system uses an
external timing source, such as vsync" — and on real hardware, it was:
an `addUpdateListener` counting real calls/sec confirmed a supposedly
"30fps-capped" animation was actually still running at 55-62 updates/sec,
the full native ~60Hz vsync rate, completely uncapped. **Don't trust this
API's own throttling — measure the real callback rate directly before
relying on it.**

The actual fix: replace the property animator with a single
`ValueAnimator(0f, 1f)` whose update listener gates the real
property-write/invalidate/draw work to genuine elapsed time via
`SystemClock.elapsedRealtime()`, computing the interpolated value from
the animator's own fraction so a skipped tick never causes drift (the
animation still finishes exactly on time regardless of how many ticks
were skipped along the way).

This was, by a wide margin, the single largest performance win found —
larger than every other optimization combined. Measured on the reference
hardware, going from a secretly-uncapped ~60fps Ken Burns pan/zoom to a
genuinely-capped 30fps:

| | Uncapped (~60fps) | Genuinely capped (30fps) |
|---|---|---|
| Janky frames | 71.4% | **8.34%** |
| Frame time 50th/90th percentile | 19/32ms | **9/15ms** |
| Frame time 95th/99th percentile | 36/48ms | **18/27ms** |

The lesson generalizes beyond Ken Burns specifically: **a continuously-
recurring per-frame animation that runs during every single transition,
all day, is worth halving the update rate of far more than any one-off
optimization elsewhere** — on hardware this constrained, rendering
workload during idle/ambient states (not just active interaction) is
often the dominant cost.

## Enable R8/code shrinking

A straightforward win with no measured downside — enabling R8 (Android's
code shrinker/optimizer) for the release build reduced APK size and
measurably improved throttle/frame-timing metrics on top of every other
fix, at zero cost. Worth turning on by default for a release build
targeting constrained hardware; there's rarely a reason not to once your
ProGuard/R8 keep rules are correct for your dependencies (test the actual
release build, not just debug, after enabling this — shrinking can break
reflection-based library usage that a debug build never exercises).

## Overdraw: check your view hierarchy directly if the standard debug tool doesn't work

The standard "Debug GPU overdraw" mechanism
(`adb shell settings put global debug_hwui_overdraw show`) can have **no
visible effect on a `user`-build device** (as opposed to `userdebug`/
`eng`) even though the setting itself is written and read back correctly
— several HWUI debug visualizations are commonly stripped or
non-functional at the vendor/framework level on production builds, even
though the underlying `Settings.Global` plumbing still works.

Check `getprop ro.build.type` — if it's `user`, don't trust that overdraw
debugging is unavailable just because the on-screen tool shows nothing.
**Fall back to reading your own view hierarchy/XML directly instead**: a
root `FrameLayout` with an explicit opaque `android:background`, layered
under a theme with no `windowBackground` override (inheriting the
platform theme's own default), is a classic redundant-paint-pass pattern
— two full-screen opaque draws every single frame, for the entire
lifetime of the Activity, when whatever's actually visible (a photo view,
a video surface, a loading overlay) already covers 100% of the screen in
every real state. Removing both (root background, and setting
`android:windowBackground` to `@null` in the theme) is safe whenever your
own content view is confirmed to always fully cover the screen — verify
this by checking your renderer's own visibility-toggling logic, not by
assumption.

## Memory budget

A reasonable target on ~1GB-RAM Android hardware, per Google's own
published guidance for this device class: steady-state app PSS well
under ~200MB, with peaks staying under ~250MB. Concretely for a
photo-slideshow app: since Android 8, decoded bitmap pixels live in
**native** heap, not Java heap — a single full-panel 1920×1080
`ARGB_8888` bitmap costs ~7.9MiB, and a Ken Burns pan/zoom that
oversamples slightly to allow headroom for the zoom (e.g. ~1.2×) pushes
that to ~11MiB *per decoded bitmap held*. Keep a deliberate cap on how
many are ever alive at once (current + optionally the next preloaded +
one more during a crossfade transition) rather than letting a cache grow
unbounded.

`Bitmap.Config.HARDWARE` (GPU-backed bitmap storage, avoiding a
CPU-side copy) is worth considering but was deliberately left disabled
here — image-loading libraries that support it commonly only recommend
it from API 28+, citing stability issues on API 27-era devices
specifically, which is exactly this hardware class's Android version.

`android:largeHeap="true"` was deliberately **not** set — a larger heap
ceiling doesn't guarantee more genuinely usable memory; it mostly shifts
where pressure gets absorbed (compressed swap/zram, if present) rather
than solving a real constraint, and can make memory problems harder to
notice until they're worse.

## A degradation ladder isn't always worth building

A tiered "Normal / Constrained / Critical" runtime mode switch (relaxing
animation/quality further as thermal/memory pressure increases) was
considered and **declined** after actually measuring the device's
real-world state distribution: across every soak-test run captured, the
device sat at thermal cooling-state 4 or higher (of a max 7) in over 98%
of samples — a genuinely cool/unthrottled state essentially never occurs
during normal operation on this hardware. A tiered ladder needs a real
"Normal" tier to step down *from* for the pattern to do anything useful;
here the device permanently sits in the upper half of its thermal
range, so the added state-machine complexity (plus its own test coverage
and hysteresis tuning) wouldn't have bought anything a single static fix
(the Ken Burns fps cap above) didn't already cover. Worth checking your
own device's actual state distribution before building adaptive
degradation logic — it's easy to assume you need it without confirming
there's a real "good" state to fall back to.

One related observation: GPU devfreq stayed at its lowest frequency step
even while the CPU was heavily thermally throttled — a useful signal that
the dominant thermal/performance driver on this hardware was CPU-bound
application work, not GPU compositing load, at least for this app's
rendering profile. Worth checking both independently (`/sys/class/thermal/`
and your SoC's devfreq cooling devices) rather than assuming which one is
the actual bottleneck.

## Thermal throttling and CPU governor

See [`../scripts/cpu-governor/`](../scripts/cpu-governor/) for the
install itself and the measured before/after. One additional finding
worth knowing if you're tempted to go further: **manually capping CPU
frequency via `scaling_max_freq` doesn't produce a stable, testable
"capped" state on a device that's already thermally throttling** — the
kernel's own cooling device continuously re-clamps the effective ceiling
in real time regardless of what userspace writes, drifting back to its
own chosen value within a second or two even if nothing re-writes the
sysfs node. On a device with active thermal pressure, "throttling" and "a
manually lower frequency cap" aren't separable, testable conditions —
there's no genuinely uncapped baseline left to compare against once
thermal pressure exists at all. If you want a more responsive governor
without fighting the kernel's own control loop, a governor switch (like
`cpu-governor/`) is the higher-leverage, non-adversarial lever.
