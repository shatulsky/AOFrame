# Fixed Wi-Fi MAC address — `/data/wifimac.txt`

Gives the frame the same Wi-Fi MAC address on every boot. Without it, some
frames come up with a new random MAC address after each reboot, so the
router sees a new device every time: DHCP reservations, per-device mesh
settings (band, access point, roaming) and parental/firewall rules all
stop applying.

No app, no `/system` change, no init script: it is one text file in
`/data` that the Wi-Fi driver itself reads when it loads.

## Does this apply to your frame?

1. Check whether the MAC address changes. Run this, reboot, and run it again:

   ```
   adb shell ip addr show wlan0 | grep ether
   ```

   If the two addresses differ, your frame has this problem.

2. Check the driver's boot log for the cause:

   ```
   adb shell su -c 'dmesg | grep -i -E "macaddr|wifi mac|random MAC"'
   ```

   On the reference unit (Realtek `8723cs` SDIO chip on a Rockchip board)
   this shows:

   ```
   RTW: rtw_read_macaddr_from_file /data/wifimac.txt is not readable
   [WLAN_RFKILL]: get_wifi_addr_vendor: rk_vendor_read wifi mac address failed (-1)
   RTW: ERROR invalid mac addr:00:00:00:00:00:00, assign random MAC
   ```

   The chip's eFuse is empty and the Rockchip vendor storage holds no MAC
   address, so the driver makes one up. Before it does, it reads
   `/data/wifimac.txt`. If your log names a different path, use that path
   below. If your log has no file line at all, check the driver module for
   one:

   ```
   adb shell su -c 'strings /vendor/lib/modules/wifi/*.ko | grep -E "wifimac|wifi_mac"'
   ```

## Prerequisites

- Network or USB ADB working, and root via `su` (see
  [`../../docs/hardware-and-root.md`](../../docs/hardware-and-root.md)).
- SELinux in **Permissive** mode (`adb shell getenforce`). The driver reads
  the file from the Wi-Fi HAL's SELinux domain, which the stock policy does
  not allow to read `/data`. In Permissive mode the read only logs an
  `avc: denied ... name="wifimac.txt" ... permissive=1` line and works. On
  an Enforcing device the driver falls back to a random MAC address.

## Install

1. Pick the MAC address. The easiest choice is the one the frame has right
   now (from `ip addr show wlan0` above), because your router already
   knows it. Any other unique unicast address works too.
2. Write it as exactly 17 characters, lower-case, colon-separated, with no
   newline:

   ```
   adb shell su -c 'printf aa:bb:cc:dd:ee:ff > /data/wifimac.txt; chmod 644 /data/wifimac.txt'
   adb shell su -c 'wc -c /data/wifimac.txt'
   ```

   The second command must print `17`.
3. Reboot:

   ```
   adb reboot
   ```

## Verify

After the reboot:

```
adb shell ip addr show wlan0 | grep ether
adb shell su -c 'dmesg | grep -i -E "wifi_mac file|random MAC"'
```

- Pass: the MAC address is the one in the file, and the log has
  `RTW: wifi_mac file: /data/wifimac.txt` and no `assign random MAC` line.
- Fail: the log still says `is not readable` or `assign random MAC`. Check
  the file path, its length, and `getenforce`.

Reboot once more to be sure the address stays the same.

## Uninstall / revert

```
adb shell su -c 'rm /data/wifimac.txt'
adb reboot
```

The frame goes back to a random MAC address on every boot.

## Notes / caveats

- **A factory reset erases `/data`**, and this file with it. Write it again
  afterward.
- After this fix, a router-side DHCP reservation becomes reliable. You can
  use it instead of, or together with, a static IP set on the frame (see
  [`../../docs/hardware-and-root.md`](../../docs/hardware-and-root.md)).
