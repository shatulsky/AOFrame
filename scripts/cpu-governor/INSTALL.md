# Installing `cpu-governor.rc` / `cpu-governor.sh` — switch to the `conservative` CPU governor

Switches all 4 CPU cores from the stock `interactive` governor to
`conservative` on every boot, re-applied via an init trigger (governors
reset to the kernel default on reboot). Same install pattern as
[`adbtcp.rc`](../adbtcp/INSTALL.md) — a custom Android init trigger
dropped into `/system/etc/init/`.

## Why

Rockchip-based frames like this one tend to throttle thermally under
sustained load in an enclosed case — worth checking your own device's
`cooling_device*/cur_state` and `thermal_zone*/temp` under
`/sys/class/thermal/` if the slideshow feels like it's slowing down over
time (see [`../../docs/hardware-and-root.md`](../../docs/hardware-and-root.md)).

`interactive` (Android's default responsive governor) ramps to max CPU
frequency aggressively on any load spike — reasonable for a phone being
actively tapped on, wasteful for a mostly-static photo/video slideshow
that doesn't need that responsiveness. Switching to `conservative` (which
ramps up more gradually) measurably reduced both temperature and
throttling *at the same time* as allowing genuinely higher clock speeds
on the reference hardware this was developed against — cooler and faster
at once, not a tradeoff, since less heat generated means the thermal
framework relaxes its own throttle cap sooner.

## What it does

`cpu-governor.sh` (installed to `/system/etc/cpu-governor.sh`) writes
`conservative` to `/sys/devices/system/cpu/cpu{0,1,2,3}/cpufreq/scaling_governor`,
verifies each core actually took it, and retries up to 10 times 1s apart
(root daemon readiness can vary boot-to-boot on some devices). Logs to
`/data/local/tmp/cpu-governor.log` (`GOVERNOR_ACTIVE` on success).
Idempotent — just re-writes the same value every boot, no
delete-then-recreate step needed.

`cpu-governor.rc` fires the script on `sys.boot_completed=1`.

## Prerequisites

- ADB access working (USB or network), authorized.
- Root available via `su`. Writing to `/sys` doesn't need `su -mm`
  (mount-master) the way a `/system` remount does — only the step below
  that copies the script/`.rc` file into `/system` needs it.
- `conservative` present in your device's `scaling_available_governors`:
  ```
  adb shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors
  ```

## Install

From this directory, with the frame connected (USB or network ADB):

```
adb push cpu-governor.sh /data/local/tmp/cpu-governor.sh
adb push cpu-governor.rc /data/local/tmp/cpu-governor.rc
adb shell su --daemon
adb shell su -mm -c "mount -o rw,remount /system && \
  cp /data/local/tmp/cpu-governor.sh /system/etc/cpu-governor.sh && \
  cp /data/local/tmp/cpu-governor.rc /system/etc/init/cpu-governor.rc && \
  chmod 755 /system/etc/cpu-governor.sh && \
  chmod 644 /system/etc/init/cpu-governor.rc && \
  chown root:root /system/etc/cpu-governor.sh /system/etc/init/cpu-governor.rc && \
  mount -o ro,remount /system"
adb reboot
```

If more than one ADB transport is connected, target the right one
explicitly with `adb -s <serial-or-ip:port> ...`.

## Verify

After the reboot completes:

```
adb shell cat /data/local/tmp/cpu-governor.log
```

Should show `GOVERNOR_ACTIVE`. Then confirm directly:

```
adb shell "for i in 0 1 2 3; do cat /sys/devices/system/cpu/cpu\$i/cpufreq/scaling_governor; done"
```

All 4 lines should read `conservative`.

## Uninstall / revert

Fully reversible — this only adds two files, nothing existing is
modified. Also revert the live governor immediately if you don't want to
wait for a reboot:

```
adb shell "for i in 0 1 2 3; do su -c 'echo interactive > /sys/devices/system/cpu/cpu'\$i'/cpufreq/scaling_governor'; done"
adb shell su --daemon
adb shell su -mm -c "mount -o rw,remount /system && rm /system/etc/cpu-governor.sh /system/etc/init/cpu-governor.rc && mount -o ro,remount /system"
adb reboot
```

After this, the governor reverts to the stock `interactive` default and
no override reapplies on future boots.

## Notes / caveats

- **`/system` is briefly remounted read-write**, then remounted back to
  read-only — a well-understood, reversible operation.
- **If the script has a syntax error**, init just logs a parse error for
  that file and continues booting normally — worst case the governor
  silently stays at its kernel default and nothing else is affected.
- **Does not fix underlying thermal throttling** if your case/placement
  is the real bottleneck — this only reduces how much heat the CPU
  generates for a given workload. A real airflow/placement fix is the
  higher-leverage lever if throttling still needs addressing further.
- **Only touches CPU frequency scaling** — does not directly affect any
  GPU/devfreq cooling device, though a lower overall SoC temperature can
  relax that too as a downstream effect.
