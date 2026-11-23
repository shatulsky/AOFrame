# Installing `wifi-watchdog`: Wi-Fi recovery for a frame that drops off the network

`wifi-watchdog` brings the frame back on Wi-Fi after a drop. It covers three cases:

| Case | What the script does |
|---|---|
| Screen asleep (night mode), Wi-Fi dropped | A "dark wake": wakes the screen with the backlight off so Android scans again. Back in 1 to 2 minutes instead of about 20. |
| Screen awake, gateway unreachable for 180 s | Restarts Wi-Fi (`svc wifi disable`, then `enable`). At most 3 times per hour. |
| Screen awake, frame on the wrong access point (optional) | Restarts Wi-Fi at once. At most 2 times per hour. Needs `PREFERRED_BSSID`. |

## Background

While the screen sleeps, Android 8.1 looks for known networks only with PNO (hardware scheduled scans). The Realtek `RTW` Wi-Fi driver on the reference frame (ARZOPA P156W) does not support PNO. `wificond` logs this error every 5 seconds:

```
NL80211_CMD_START_SCHED_SCAN failed: Operation not supported on transport endpoint
```

So after a Wi-Fi drop at night, the frame does not reconnect until a full scan occurs, about 20 minutes later. Tests with the screen asleep:

| Action | Result |
|---|---|
| `svc wifi disable` then `svc wifi enable` | No reconnect in 2 minutes. |
| `service call wifi 18 ...` (`IWifiManager.startScan`) | Android drops the request. No scan occurs. |
| `input keyevent KEYCODE_WAKEUP` | Reconnect in about 2 seconds. |

Only a screen wake works while the screen sleeps.

CAUTION: Do not use `service call wifi 17`. On this Android 8.1 build, 17 is `disableNetwork(netId)`, not `startScan`. It can disable your saved home network.

With the screen awake, a second fault exists: the Realtek `8723cs` driver can stall after a roam, or with no roam. The gateway then stays unreachable for 1 to 10 minutes. A restart of Wi-Fi clears it in 3 to 15 seconds.

On a mesh network with two or more nodes, a third pattern appeared on the reference frame: the stalls began while the frame sat on the weaker node, about 50 minutes after a roam to it. A pin in the router app did not keep the frame on the good node. The optional wrong-access-point check handles this: when the BSSID differs from `PREFERRED_BSSID`, the script restarts Wi-Fi, and the frame reconnects to the stronger node.

## What it does

`wifi-watchdog.rc` starts `/system/etc/wifi-watchdog.sh` as a background init `service` after boot. It is not an `exec`, because an `exec` blocks all later boot actions until it exits, and this script never exits.

The script:

1. Waits 120 seconds after boot.
2. Pings the gateway every 30 seconds, and logs each roam (`roam: BSSID a -> b`).
3. Does nothing if Wi-Fi is off in Android, or if the gateway answers.
4. Screen awake and 180 seconds of failed pings: `svc wifi disable`, 3 seconds, `svc wifi enable`, then waits up to 30 seconds for the gateway. At most 3 times per hour (`/data/local/tmp/wifi-watchdog.restarts`), so a real outage causes no restart loop.
5. Screen awake and `PREFERRED_BSSID` set, and the frame is on another BSSID: the same restart, with its own limit of 2 per hour (`/data/local/tmp/wifi-watchdog.wrongunit`). If the preferred access point is down, the frame stays on the other one and the limit stops a loop.
6. Screen asleep and 2 failed pings in a row (about 60 seconds): a dark wake.
   1. Saves `screen_brightness` to `/data/local/tmp/wifi-watchdog.brightness`, then sets it to `1`.
   2. Wakes the screen (`KEYCODE_WAKEUP`).
   3. Writes `0` to the backlight each time Android turns it on.
   4. Waits up to 30 seconds for the gateway.
   5. Puts the screen back to sleep, then restores `screen_brightness`.
7. Waits 270 seconds after a dark wake, then tries again if the frame is still offline.

The script skips the dark wake from 3 minutes before until 2 minutes after the night-mode wake time. It reads that time from `/data/data/dev.aoframe/files/night-mode.json`. Without this, the app's own wake could fire during a dark wake, and the script would put the screen back to sleep for the whole day.

The log is `/data/local/tmp/wifi-watchdog.log`. A new log starts at 64 KB (the old one becomes `wifi-watchdog.log.old`).

## Before you start

1. ADB works. `adb devices` shows the frame as `device`.
2. Root is available through `su`. See [`../../docs/hardware-and-root.md`](../../docs/hardware-and-root.md).
3. Edit `wifi-watchdog.sh`:
   - Set `GATEWAY` to your router IP.
   - Optional: set `PREFERRED_BSSID` to the BSSID of the access point the frame should use (for example `00:11:22:33:44:55`). Find it with `adb shell dumpsys wifi | grep mWifiInfo` while the frame is on that node. Leave it empty on a single-router network.

## Install

CAUTION: Use `su -mm` (mount-master mode), not plain `su -c`. With plain `su -c`, the remount can fail without an error.

CAUTION: Run the commands from Git Bash, and keep the outer double quotes. If the quotes are lost, only `mount` runs as root, the `cp` fails, and `/system` stays writable.

1. Go to this folder.
2. Turn off Git Bash path conversion for this shell:
   ```
   export MSYS_NO_PATHCONV=1
   ```
3. Copy the files to the frame:
   ```
   adb push wifi-watchdog.sh /data/local/tmp/wifi-watchdog.sh
   adb push wifi-watchdog.rc /data/local/tmp/wifi-watchdog.rc
   ```
4. Start the root daemon:
   ```
   adb shell su --daemon
   ```
5. Install the files into `/system`:
   ```
   adb shell "su -mm -c 'mount -o rw,remount /system && cp /data/local/tmp/wifi-watchdog.sh /system/etc/wifi-watchdog.sh && chmod 755 /system/etc/wifi-watchdog.sh && chown root:root /system/etc/wifi-watchdog.sh && cp /data/local/tmp/wifi-watchdog.rc /system/etc/init/wifi-watchdog.rc && chmod 644 /system/etc/init/wifi-watchdog.rc && chown root:root /system/etc/init/wifi-watchdog.rc; mount -o ro,remount /system'"
   ```
6. Make sure that `/system` is read-only again. The output must contain `ro,`:
   ```
   adb shell "mount | grep ' /system '"
   ```
7. Reboot the frame:
   ```
   adb reboot
   ```

To change only the script later, do steps 3 to 6, then run `adb shell "su -c 'setprop ctl.restart wifi-watchdog'"` instead of a reboot.

## Verify

1. Wait about 30 seconds after boot. Make sure that the service runs. The output must be `running`:
   ```
   adb shell getprop init.svc.wifi-watchdog
   ```
2. Read the log. It must contain a `started` line:
   ```
   adb shell "su -c 'cat /data/local/tmp/wifi-watchdog.log'"
   ```

After a real event, the log shows lines like these:

```
wifi stall for 180s, screen awake: svc wifi disable/enable (BSSID before: 00:11:22:33:44:66)
wifi restart: gateway reachable after 5s (BSSID after: 00:11:22:33:44:55)
frame on wrong unit (00:11:22:33:44:66), screen awake: svc wifi disable/enable (BSSID before: 00:11:22:33:44:66)
dark wake: gateway reachable after 3s
```

## Remove

The install adds two files and changes no other file.

1. Remove the files from `/system`:
   ```
   adb shell "su -mm -c 'mount -o rw,remount /system && rm /system/etc/init/wifi-watchdog.rc /system/etc/wifi-watchdog.sh; mount -o ro,remount /system'"
   ```
2. Reboot the frame:
   ```
   adb reboot
   ```
3. Make sure that `screen_brightness` is your normal value. If a dark wake was cut off, it can stay at `1`:
   ```
   adb shell settings get system screen_brightness
   ```

## Risks and limits

- **The gateway IP is fixed in the script.** If the router IP changes, change `GATEWAY` and install again.
- **The Wi-Fi restart works only with the screen awake.** While the screen sleeps, the dark wake is the only method that works.
- **A stopped watchdog can leave the brightness at `1`.** On each start, the script restores the value from `/data/local/tmp/wifi-watchdog.brightness`, so a reboot repairs it.
- **A dark wake can wake the app.** The app sees a normal screen wake and sleep.
- **A long outage causes a dark wake about every 5 minutes.** Each one takes less than about 35 seconds.
- **The wrong-access-point restart drops the link for about 5 seconds.**
- **An OTA update can remove the files.** Do the install again after the update.
