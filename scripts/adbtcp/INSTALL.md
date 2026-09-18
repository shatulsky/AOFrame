# Installing `adbtcp.rc` — persistent network ADB

Makes ADB over Wi-Fi (port 5555) survive reboots on a rooted Frameo-based
frame, without installing any app on the device. This is exactly what
running `adb tcpip 5555` by hand does, just automated on every boot via a
custom Android init trigger.

## Prerequisites

- ADB access already working over USB, and authorized (`adb devices`
  shows the frame as `device`, not `unauthorized`).
- Root available via `su` (see [`../../docs/hardware-and-root.md`](../../docs/hardware-and-root.md)).
- **Use `su -mm` (mount-master), not plain `su -c`, for the `/system`
  remount below** — plain `su -c "mount -o rw,remount /system && ..."`
  can silently fail to grant real write access on SuperSU-rooted devices
  (SuperSU gives a plain `su -c` shell its own private mount namespace
  that doesn't propagate to other processes) — see
  [`../../docs/hardware-and-root.md`](../../docs/hardware-and-root.md)
  for the full finding and how to tell if this affects your device.

## Install

From this directory, with the frame connected via USB:

```
adb push adbtcp.rc /data/local/tmp/adbtcp.rc
adb shell su --daemon
adb shell su -mm -c "mount -o rw,remount /system && cp /data/local/tmp/adbtcp.rc /system/etc/init/adbtcp.rc && chmod 644 /system/etc/init/adbtcp.rc && chown root:root /system/etc/init/adbtcp.rc && mount -o ro,remount /system"
adb reboot
```

If more than one ADB transport is connected (USB + an existing network
session), target the USB one explicitly with `adb -s <serial> ...` (get
the serial from `adb devices -l`).

## Verify

After the reboot completes:

```
adb connect <frame-ip>:5555
adb devices -l
```

You should see the frame listed as `device` (not `unauthorized`, not
missing) at `<frame-ip>:5555`, with no manual `adb tcpip 5555` step
required.

## Uninstall / revert

Fully reversible — this only adds one file, nothing existing is modified:

```
adb shell su -mm -c "mount -o rw,remount /system && rm /system/etc/init/adbtcp.rc && mount -o ro,remount /system"
adb reboot
```

After this, network ADB goes back to requiring a manual `adb tcpip 5555`
over USB each session, same as before.

## Notes / caveats

- **`/system` is briefly remounted read-write** to install the file, then
  remounted back to read-only. This is a standard, well-understood
  operation on rooted Android and doesn't touch `boot.img`, kernel,
  bootloader, or partition tables — the file itself persists on the
  partition regardless of mount mode afterward.
- **If the init file has a syntax error**, Android's init process just
  logs a parse error for that one file and continues booting normally —
  it does not halt boot. Worst case: the trigger silently doesn't fire,
  and the frame behaves exactly as it did before (manual `adb tcpip 5555`
  still needed).
- **A full OTA update could wipe this** on a device with a silent
  OTA/FOTA agent still active. Not a safety issue — just means re-running
  the install steps above afterward.
- **Never expose port 5555 to the internet** — keep it LAN-only or behind
  a VPN.
