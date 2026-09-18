#!/usr/bin/env bash
# On-device performance soak sampler for dev.aoframe - a before/after
# comparison tool for changes like the Ken Burns fps cap. Samples SoC/GPU
# temp, CPU/GPU cooling-device throttle state, per-core CPU frequency,
# load average, and the app's PSS every INTERVAL seconds over the whole
# window via one on-device `sh` loop (avoids one adb round-trip per
# sample), and brackets the window with a `dumpsys gfxinfo reset`/read for
# jank/frame-time percentiles.
#
# Deliberately NOT a macrobenchmark module - this measures ordinary
# sustained execution (thermal/frame-timing/memory while the slideshow
# just runs), not cold-start, so there's no repeated-launch benchmark to
# write.
#
# Usage: ./soak-test.sh [label] [duration_seconds] [interval_seconds]
#   ./soak-test.sh before         # 300s window, 10s samples (defaults)
#   ./soak-test.sh after 600 15   # 600s window, 15s samples
#
# Writes soak-results/soak_<label>.csv and soak-results/gfxinfo_<label>.txt,
# and prints a summary at the end. Run once against whatever build is
# currently installed/running on the frame, install a new build, then
# run again with a different label to compare.
#
# Reads the frame's ADB address from immich-secrets.json's "frameAddr"
# field (copy immich-secrets.example.json to immich-secrets.json and fill
# in your own values first) rather than hardcoding it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LABEL="${1:-run}"
DURATION="${2:-300}"
INTERVAL="${3:-10}"
SAMPLES=$(( DURATION / INTERVAL ))

SECRETS_FILE="$SCRIPT_DIR/immich-secrets.json"
if [ ! -f "$SECRETS_FILE" ]; then
	echo "$SECRETS_FILE not found - copy immich-secrets.example.json to immich-secrets.json and fill in your own values first." >&2
	exit 1
fi

FRAME_ADDR="$(node -e "const c=require('$SECRETS_FILE'); if(!c.frameAddr) throw new Error('missing frameAddr'); console.log(c.frameAddr)")"
PACKAGE="dev.aoframe"
OUT_DIR="$SCRIPT_DIR/soak-results"
DEVICE_SCRIPT=/data/local/tmp/soak.sh
DEVICE_CSV=/data/local/tmp/soak_out.csv

mkdir -p "$OUT_DIR"
# adb.exe is a native Windows binary - git-bash's MSYS path-conversion
# layer mangles absolute-looking unix paths (both the /data/local/tmp
# remote paths below AND, with MSYS_NO_PATHCONV set to protect those, an
# absolute local path too). cd'ing here and using bare relative
# filenames for every local push/pull argument sidesteps both problems
# at once (same trick release.sh already uses for its own adb push).
cd "$OUT_DIR"

echo "Connecting to the frame ($FRAME_ADDR)..."
adb connect "$FRAME_ADDR" >/dev/null

# Generated fresh each run (not committed separately) so SAMPLES/INTERVAL
# stay in sync with the args above without templating.
cat > .device-soak.sh <<EOF
OUT=$DEVICE_CSV
echo "ts,soc_mC,gpu_mC,cpu_cool_state,cpu_cool_max,gpu_cool_state,gpu_cool_max,freq0,freq1,freq2,freq3,load1,load5,load15,pss_kb" > "\$OUT"
i=0
while [ \$i -lt $SAMPLES ]; do
  ts=\$(date +%s)
  soc=\$(cat /sys/class/thermal/thermal_zone0/temp)
  gpu=\$(cat /sys/class/thermal/thermal_zone1/temp)
  ccs=\$(cat /sys/class/thermal/cooling_device0/cur_state)
  ccm=\$(cat /sys/class/thermal/cooling_device0/max_state)
  gcs=\$(cat /sys/class/thermal/cooling_device1/cur_state)
  gcm=\$(cat /sys/class/thermal/cooling_device1/max_state)
  f0=\$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq)
  f1=\$(cat /sys/devices/system/cpu/cpu1/cpufreq/scaling_cur_freq)
  f2=\$(cat /sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq)
  f3=\$(cat /sys/devices/system/cpu/cpu3/cpufreq/scaling_cur_freq)
  load=\$(cat /proc/loadavg | tr -s ' ' | cut -d' ' -f1-3 | tr ' ' ',')
  pss=\$(dumpsys meminfo $PACKAGE | tr -s ' ' | grep '^ TOTAL ' | cut -d' ' -f3)
  echo "\$ts,\$soc,\$gpu,\$ccs,\$ccm,\$gcs,\$gcm,\$f0,\$f1,\$f2,\$f3,\$load,\$pss" >> "\$OUT"
  sleep $INTERVAL
  i=\$((i+1))
done
echo DONE
EOF

MSYS_NO_PATHCONV=1 adb -s "$FRAME_ADDR" push .device-soak.sh "$DEVICE_SCRIPT" >/dev/null
rm -f .device-soak.sh

MSYS_NO_PATHCONV=1 adb -s "$FRAME_ADDR" shell "dumpsys gfxinfo $PACKAGE reset" >/dev/null

echo "Sampling for ${DURATION}s (${SAMPLES} x ${INTERVAL}s), label: $LABEL..."
MSYS_NO_PATHCONV=1 adb -s "$FRAME_ADDR" shell "sh $DEVICE_SCRIPT"

CSV="soak_${LABEL}.csv"
GFXINFO="gfxinfo_${LABEL}.txt"
MSYS_NO_PATHCONV=1 adb -s "$FRAME_ADDR" shell "dumpsys gfxinfo $PACKAGE" > "$GFXINFO"
MSYS_NO_PATHCONV=1 adb -s "$FRAME_ADDR" pull "$DEVICE_CSV" "$CSV" >/dev/null

echo
echo "=== $LABEL summary ==="
awk -F, 'NR>1 {
    n++; soc+=$2/1000; gpu+=$3/1000;
    if(n==1 || $2/1000>maxsoc) maxsoc=$2/1000; if(minsoc==""||$2/1000<minsoc) minsoc=$2/1000;
    cc+=$4; if($4==7) atmax++;
    if($8==408000||$9==408000||$10==408000||$11==408000) floorsum++;
    fsum+=$8+$9+$10+$11; fcount+=4;
    if($15!="") { pss+=$15; pcount++; if(pcount==1||$15>pss_max) pss_max=$15; if(pss_min==""||$15<pss_min) pss_min=$15; }
} END {
    printf "SoC temp C: avg=%.1f min=%.1f max=%.1f\n", soc/n, minsoc, maxsoc;
    printf "GPU temp C: avg=%.1f\n", gpu/n;
    printf "CPU cooling state: avg=%.2f, at max(7) in %d/%d samples (%.0f%%)\n", cc/n, atmax, n, 100*atmax/n;
    printf "Samples with a core at 408MHz floor: %d/%d (%.0f%%)\n", floorsum, n, 100*floorsum/n;
    printf "avg core freq MHz: %.0f\n", (fsum/fcount)/1000;
    if (pcount>0) printf "PSS MB: avg=%.1f min=%.1f max=%.1f\n", (pss/pcount)/1024, pss_min/1024, pss_max/1024;
}' "$CSV"
grep -E "Total frames rendered|Janky frames|percentile" "$GFXINFO"
echo
echo "Wrote $OUT_DIR/$CSV and $OUT_DIR/$GFXINFO"
