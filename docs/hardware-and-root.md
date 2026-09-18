# Hardware & root access

AOFrame was developed against an **ARZOPA P156W** digital photo frame, but
almost everything here applies to any Frameo-based frame — these run on
generic, rebranded Android hardware from a handful of common ODMs, so a
different model is likely to behave the same way.

## What kind of device this is

- Runs Android under the hood (confirmed: **Android 8.1, API 27** on the
  reference unit), with Frameo's own app acting as a full-screen
  launcher/kiosk on top. The underlying Android system is fully intact —
  Frameo's UI just doesn't expose it.
- Common SoC family: Rockchip (`RK3326` on the reference unit — 4×
  Cortex-A35, low clock ceiling, passively cooled in an enclosed case).
  Check yours via `adb shell getprop ro.board.platform`.
- No Play Store, no Google Mobile Services on these devices — expect a
  minimal package set and no way to legitimately license/update a
  sideloaded app.

## Root access

Frameo-based frames commonly ship with **root already present** via a
bundled `su` binary (SuperSU, on the reference unit), even with no
manager app installed. Check with:

```
adb shell su -c id
```

If that returns `uid=0(root)`, root is already available — no
exploit/unlock needed. Also worth checking:

```
adb shell getenforce                        # often "Permissive" - access control largely advisory
adb shell getprop ro.boot.verifiedbootstate # "orange" = unlocked, per AOSP convention
adb shell getprop ro.crypto.state           # "unsupported" = no storage encryption
```

### `/system` writes may need mount-master mode

**Plain `su -c "mount -o rw,remount /system && ..."` can silently fail to
grant real write access**, even though it reports success and lets
`touch` create new files that persist. The cause: some `su`
implementations (SuperSU included) give a plain `su -c` shell its own
private mount namespace — a remount done there doesn't propagate to the
namespace every other process (including a later `adb shell` session)
actually sees, so subsequent real content writes (`cp`, etc.) fail with
"Read-only file system" even in the exact directory a `touch` just
succeeded in.

**Fix**: start the root daemon, then use mount-master mode, which
operates in the real, shared mount namespace:

```
adb shell su --daemon
adb shell su -mm -c "mount -o rw,remount /system && <your write here>"
```

If your device's `su` doesn't support `-mm`/`--mount-master` (this is a
SuperSU-specific flag, not universal), check whether writes made via
plain `su -c` are actually visible from a *separate* `adb shell` session
afterward before trusting them.

### Custom init triggers need an explicit SELinux `seclabel`

A custom `.rc` file dropped into `/system/etc/init/` using
`on property:sys.boot_completed=1` + `exec - root root -- <cmd>` (the
`-` meaning "let init figure out the SELinux domain") can **fail
silently at boot** with `init: service exec N (<cmd>) does not have a
SELinux domain defined` — even with SELinux in Permissive mode globally,
since init's own domain-resolution check for a brand-new file isn't
gated by enforcing/permissive.

**Fix**: specify an explicit `seclabel` instead of `-`, matching the
context a normal `adb shell` command runs under:

```
exec u:r:shell:s0 shell shell -- <cmd>
```

(Not needed for init built-ins like `setprop`/`stop`/`start` — only for
`exec`-ing an actual binary/script. See [`../scripts/adbtcp/`](../scripts/adbtcp/)
for an example that doesn't need this, and
[`../scripts/cpu-governor/`](../scripts/cpu-governor/) for one that does.)

### Multiple boot scripts run in one sequential queue

If you install more than one custom init trigger under the same
`on property:sys.boot_completed=1` condition, Android init combines them
into **one sequential action queue**, processed in file-parse order
(alphabetical by filename in `/system/etc/init/`). `exec` **blocks that
queue until the spawned process exits** — it is not fire-and-forget. A
slow earlier-parsed script pushes back every later-parsed script's real
start time by its own full runtime. Worth checking
`dmesg | grep "starting service 'exec"` if a later boot script's effect
doesn't show up as quickly as expected.

## ADB access

### Getting the frame to enumerate as a USB device at all

Enabling Frameo's own "ADB Access" toggle alone (Settings → About → Beta
Program → ADB Access) is often **not sufficient** — the device may still
not enumerate as any kind of USB device to your computer at all. If
`adb devices` shows nothing and the cable/port are known-good, the
frame's USB port is very likely still in charging-only/analog mode.

On the reference unit, the actual fix was a *separate* USB-mode setting:
**Settings → Manage photos → Transfer from computer → "Enable transfer
from computer"** (and/or explicitly choosing "storage device" as the USB
connection mode when prompted). Look for an equivalent "USB
storage/transfer" toggle on your device if the ADB toggle alone doesn't
work — it's a common two-toggle requirement on this class of hardware,
not specific to one model.

A confirmed-working cable/port combination was USB-C-to-USB-A (USB-C into
the frame, USB-A into the PC) — try that combination first if a
USB-C-to-USB-C connection reports "Analog USB devices aren't supported."

### Native Android Settings are still reachable via ADB

Frameo's kiosk UI has no entry point into the underlying Android Settings
app, but the real system Settings activities are fully present and
functional — just not linked to from Frameo's own menus:

```
adb shell am start -a android.settings.WIFI_SETTINGS
```

This works for other standard `Settings` intents too
(`android.settings.SETTINGS`, `android.settings.APPLICATION_DETAILS_SETTINGS`,
etc.). Frameo's "kiosk lock" is a launcher/UI-level restriction only, not
a removal of underlying Android functionality.

### A stable IP matters more than it might seem

If your frame's Wi-Fi MAC address isn't stable across reboots (check via
`adb shell ip addr show wlan0` across a couple of reboots — some budget
Wi-Fi drivers don't have a real MAC burned into NVRAM and generate a
pseudo-random one each boot), a router-side DHCP reservation won't be
reliable. Setting a **static IP directly on the device** (via the native
Wi-Fi settings screen above, not Frameo's own menu) avoids that entirely:
tap the network → Advanced options → IP settings → Static.

See [`../scripts/adbtcp/`](../scripts/adbtcp/) for making network ADB
(port 5555) itself survive reboots without a static IP being strictly
required, though it makes reconnecting far more convenient.

### Backlight/brightness may need a different mechanism than `Settings.System`

`adb shell settings put system screen_brightness <n>` can silently no-op
on the physical backlight on some of these devices, even though the
setting value changes and reads back correctly. If brightness changes
aren't taking effect, check for a direct sysfs backlight node instead:

```
adb shell su -c 'echo <0-255> > /sys/class/backlight/backlight/brightness'
```

## Launching Frameo's own app directly

Useful for reverting to stock Frameo, or confirming your frame's
launcher activity name:

```
adb shell dumpsys package net.frameo.frame | grep LAUNCHER
adb shell am start -n net.frameo.frame/.ui.activities.AWaitForDeferredInitCompleted
```

(The exact activity name may differ by Frameo app version — confirm via
the `dumpsys` command above rather than assuming it matches.)

## Security posture

- **Never expose ADB port 5555 to the internet** once network ADB is
  enabled — keep it LAN-only or behind a VPN.
- A full OTA update (if your device still has an active update agent)
  could revert any `/system`-level customization from this doc. Not a
  safety concern on its own — just means re-applying the change
  afterward if it disappears.

## Windows-specific gotcha: Git Bash mangles device paths

Running `adb push local.sh /data/local/tmp/local.sh` (or any `adb`
command with an absolute-looking path) from a Git-Bash/MSYS2 shell on
Windows can silently rewrite the *remote* device path as if it were a
local Windows path — e.g. `/data/local/tmp/foo` becomes
`C:/Program Files/Git/data/local/tmp/foo` — and the command fails with a
confusing error that looks like a device-side problem but isn't. This is
MSYS2's automatic POSIX-path-to-Windows-path conversion, applied even
though the path is meant for the remote Android device.

**Fix**: set `MSYS_NO_PATHCONV=1` as an environment variable for the
command, e.g. `MSYS_NO_PATHCONV=1 adb push local.sh /data/local/tmp/local.sh`.
