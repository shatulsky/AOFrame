#!/system/bin/sh
# Wi-Fi watchdog for the ARZOPA P156W frame.
#
# Problem: while the screen sleeps (night mode), Android 8.1 looks for known
# networks only with PNO (hardware scheduled scans). This frame's Realtek RTW
# driver does not support PNO (wificond logs "NL80211_CMD_START_SCHED_SCAN
# failed: Operation not supported" every 5 s), so after any Wi-Fi drop at
# night the frame stays offline for about 20 minutes. Seen
# after a router reboot.
#
# Tested and rejected: "svc wifi disable/enable" and a direct
# IWifiManager.startScan ("service call wifi 18 ...") do nothing while the
# screen sleeps - Android drops the scan request. Only a screen wake works:
# the frame reconnects about 2 s after KEYCODE_WAKEUP.
#
# So: if the gateway has been unreachable for about 60 s while the screen
# sleeps, wake the screen with the backlight off, wait for Wi-Fi, and put the
# screen back to sleep. Started by wifi-watchdog.rc as an init service.

GATEWAY=192.168.1.1               # CHANGE: your router IP (the frame should use a static IP or a reservation)
CHECK_INTERVAL=30                # s between gateway pings
FAILS_BEFORE_ACTION=2            # 2 x 30 s = about 60 s offline
COOLDOWN=270                     # s to wait after a dark wake (retry about every 5 min)
WAKE_TIMEOUT=30                  # s to wait for Wi-Fi during a dark wake
NIGHT_MODE_FILE=/data/data/dev.aoframe/files/night-mode.json
BACKLIGHT=/sys/class/backlight/backlight/brightness
STATE=/data/local/tmp/wifi-watchdog.brightness
LOG=/data/local/tmp/wifi-watchdog.log
AWAKE_STALL_SECONDS=180          # s of failed pings with the screen awake before a Wi-Fi restart
MAX_RESTARTS_PER_HOUR=3          # limit, so a real outage (router off) causes no restart loop
RESTARTS=/data/local/tmp/wifi-watchdog.restarts
PREFERRED_BSSID=""                            # OPTIONAL: BSSID of the access point the frame should stay on, e.g. 00:11:22:33:44:55. Empty = feature off.
MAX_WRONG_UNIT_RESTARTS_PER_HOUR=2           # if the preferred access point is down, do not loop
WRONG_UNIT_RESTARTS=/data/local/tmp/wifi-watchdog.wrongunit

log() {
    # Keep the log small: start a new one at 64 KB.
    [ -f $LOG ] && [ $(wc -c < $LOG) -gt 65536 ] && mv $LOG $LOG.old
    echo "$(date '+%m-%d %H:%M:%S') $*" >> $LOG
}

gateway_ok() { ping -c1 -W2 $GATEWAY >/dev/null 2>&1; }
screen_asleep() { dumpsys power | grep -q 'mWakefulness=Asleep'; }

# Minutes since midnight for "HH:MM".
to_minutes() { echo $(( 10#${1%%:*} * 60 + 10#${1##*:} )); }

# True if the night-mode wake time is near (3 min before to 2 min after).
# Then the app's own wake trigger can fire during our dark wake, and our
# KEYCODE_SLEEP would leave the frame dark for the whole day.
near_scheduled_wake() {
    wake=$(sed -n 's/.*"wakeTime":"\([0-9]*:[0-9]*\)".*/\1/p' $NIGHT_MODE_FILE 2>/dev/null)
    [ -z "$wake" ] && return 1
    now=$(to_minutes $(date +%H:%M))
    diff=$(( now - $(to_minutes $wake) ))
    [ $diff -lt -720 ] && diff=$(( diff + 1440 ))
    [ $diff -gt 720 ] && diff=$(( diff - 1440 ))
    [ $diff -ge -3 ] && [ $diff -le 2 ]
}

# Undo a dark wake that did not finish (crash or reboot in the middle).
restore_brightness() {
    if [ -f $STATE ]; then
        settings put system screen_brightness $(cat $STATE)
        rm -f $STATE
        log "restored screen_brightness from $STATE"
    fi
}

dark_wake() {
    settings get system screen_brightness > $STATE
    settings put system screen_brightness 1
    input keyevent KEYCODE_WAKEUP
    log "dark wake: screen woken, backlight minimum"
    i=0
    while [ $i -lt $WAKE_TIMEOUT ]; do
        # Android applies screen_brightness about 1.5 s after the wake.
        # Turn the backlight fully off each time it comes on.
        [ "$(cat $BACKLIGHT)" != "0" ] && echo 0 > $BACKLIGHT
        if gateway_ok; then
            log "dark wake: gateway reachable after ${i}s"
            break
        fi
        sleep 1
        i=$((i + 1))
    done
    [ $i -ge $WAKE_TIMEOUT ] && log "dark wake: gateway still unreachable after ${WAKE_TIMEOUT}s"
    # Sleep again only if the screen is still awake (nobody turned it off).
    screen_asleep || input keyevent KEYCODE_SLEEP
    sleep 1
    restore_brightness
}

# Current BSSID, or "none". Used to log roams.
current_bssid() {
    b=$(dumpsys wifi | sed -n 's/.*mWifiInfo.*BSSID: \([0-9a-fA-F:]*\).*/\1/p' | head -n 1)
    echo "${b:-none}"
}

# Restart Wi-Fi when a stall lasts long with the screen awake.
# Allowed at most MAX_RESTARTS_PER_HOUR times; the times are kept in $RESTARTS.
# $1 = reason for the log, $2 = file with the restart times, $3 = limit per hour.
wifi_restart() {
    reason=$1
    file=$2
    limit=$3
    now=$(date +%s)
    kept=""
    count=0
    for t in $(cat $file 2>/dev/null); do
        if [ $((now - t)) -lt 3600 ]; then
            kept="$kept $t"
            count=$((count + 1))
        fi
    done
    if [ $count -ge $limit ]; then
        log "$reason, restart limit reached ($count in the last hour): no action"
        echo $kept > $file
        return
    fi
    echo "$kept $now" > $file
    before=$(current_bssid)
    log "$reason, screen awake: svc wifi disable/enable (BSSID before: $before)"
    svc wifi disable
    sleep 3
    svc wifi enable
    i=0
    while [ $i -lt $WAKE_TIMEOUT ]; do
        gateway_ok && break
        sleep 1
        i=$((i + 1))
    done
    if [ $i -ge $WAKE_TIMEOUT ]; then
        log "wifi restart: gateway still unreachable after ${WAKE_TIMEOUT}s (BSSID now: $(current_bssid))"
    else
        log "wifi restart: gateway reachable after ${i}s (BSSID after: $(current_bssid))"
    fi
}

log "started"
restore_brightness
sleep 120                        # let Wi-Fi connect after boot
fails=0
awake_fails=0
last_bssid=$(current_bssid)
while true; do
    sleep $CHECK_INTERVAL
    # Log each roam: the BSSID changed since the last check.
    b=$(current_bssid)
    if [ "$b" != "$last_bssid" ] && [ "$b" != "none" ] && [ "$last_bssid" != "none" ]; then
        log "roam: BSSID $last_bssid -> $b"
    fi
    [ "$b" != "none" ] && last_bssid=$b
    # Frame on the wrong access point: on the reference frame the stalls
    # began on a second mesh node, about 50 minutes after a roam to it. Restart
    # Wi-Fi so the frame reconnects to the preferred node. Own hourly limit,
    # so these restarts never use up the stall budget. Screen awake only:
    # svc wifi disable/enable does nothing while the screen sleeps.
    if [ -n "$PREFERRED_BSSID" ] && [ "$b" != "none" ] && [ "$b" != "$PREFERRED_BSSID" ] && [ "$(settings get global wifi_on)" = "1" ] && ! screen_asleep; then
        wifi_restart "frame on wrong unit ($b)" $WRONG_UNIT_RESTARTS $MAX_WRONG_UNIT_RESTARTS_PER_HOUR
        last_bssid=$(current_bssid)
        continue
    fi
    if [ "$(settings get global wifi_on)" != "1" ] || gateway_ok; then
        fails=0
        awake_fails=0
        continue
    fi
    # Long stall with the screen awake: restart Wi-Fi.
    if ! screen_asleep; then
        awake_fails=$((awake_fails + 1))
        if [ $((awake_fails * CHECK_INTERVAL)) -ge $AWAKE_STALL_SECONDS ]; then
            awake_fails=0
            fails=0
            wifi_restart "wifi stall for ${AWAKE_STALL_SECONDS}s" $RESTARTS $MAX_RESTARTS_PER_HOUR
        fi
        continue
    fi
    awake_fails=0
    fails=$((fails + 1))
    [ $fails -lt $FAILS_BEFORE_ACTION ] && continue
    fails=0
    if near_scheduled_wake; then
        log "gateway unreachable, scheduled wake is near: no action"
        continue
    fi
    dark_wake
    sleep $COOLDOWN
done
