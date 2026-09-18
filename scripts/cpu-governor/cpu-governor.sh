#!/system/bin/sh
# Boot-time CPU governor switch. Switches all 4 cores from the stock
# "interactive" governor to "conservative" - interactive ramps to max
# frequency aggressively on any load spike, which for a mostly-static
# photo/video slideshow generates more heat than the workload needs.
# See ../../docs/hardware-and-root.md's thermal-throttling section for
# the measured before/after. The governor resets to the kernel default
# ("interactive") on every reboot, hence needing this init-triggered
# re-application.
#
# Idempotent: just re-writes each core's scaling_governor file every
# time, no delete-then-recreate step needed - re-running this is always
# safe. Retries in case SuperSU's daemon isn't ready yet (daemon
# readiness time varies boot-to-boot on some devices).

GOVERNOR=conservative
CPUS="0 1 2 3"

su --daemon >/data/local/tmp/cpu-governor.log 2>&1
i=0
while [ $i -lt 10 ]; do
    for c in $CPUS; do
        su -c "echo $GOVERNOR > /sys/devices/system/cpu/cpu$c/cpufreq/scaling_governor" >>/data/local/tmp/cpu-governor.log 2>&1
    done

    ok=1
    for c in $CPUS; do
        grep -q "^$GOVERNOR$" "/sys/devices/system/cpu/cpu$c/cpufreq/scaling_governor" || ok=0
    done
    if [ "$ok" = "1" ]; then
        echo GOVERNOR_ACTIVE >>/data/local/tmp/cpu-governor.log
        break
    fi

    sleep 1
    i=$((i + 1))
done
