#!/usr/bin/env bash
# Runs every test layer of AOFrame on the emulator and prints the
# line coverage of each layer and of all three together.
#
#   unit         plain JVM tests (app/src/test)
#   integration  instrumented tests, every package except dev.aoframe.e2e
#   e2e          instrumented UI tests of the real screen (dev.aoframe.e2e)
#
# Needs the emulator AVD "Frame" (the script starts it headless when no
# device is attached) and never touches the real frame: it disconnects the
# network ADB address of the frame first. Instrumented tests delete the
# app's settings and bind port 8099.
#
# Usage: scripts/run-all-tests.sh [unit] [integration] [e2e]   (default: all)
#        JAVA_HOME must point to a JDK (Android Studio's bundled JBR works).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../app"
SDK="${ANDROID_HOME:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb
: "${JAVA_HOME:=C:/Program Files/Android/Android Studio/jbr}"
export JAVA_HOME

LAYERS=("$@")
[ ${#LAYERS[@]} -eq 0 ] && LAYERS=(unit integration e2e)
GRADLE="$APP/gradlew"
[ -x "$GRADLE" ] || GRADLE="$APP/gradlew.bat"

stamp() { date +%s; }
START_ALL=$(stamp)

needs_emulator=false
for layer in "${LAYERS[@]}"; do [ "$layer" != unit ] && needs_emulator=true; done

if $needs_emulator; then
  # Never run instrumented tests on the real frame.
  "$ADB" disconnect >/dev/null 2>&1 || true
  if ! "$ADB" devices | grep -q '^emulator-'; then
    echo "Starting emulator Frame (headless)..."
    nohup "$SDK/emulator/emulator" -avd Frame -no-snapshot -no-audio -no-window \
      -gpu swiftshader_indirect >/dev/null 2>&1 &
    "$ADB" wait-for-device
  fi
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
  if [ "$("$ADB" devices | grep -c 'device$')" -ne 1 ]; then
    echo "Exactly one device must be attached (the emulator)." >&2
    exit 2
  fi
fi

rm -rf "$APP/app/build/layers"
cd "$APP"

keep() {  # keep <layer>: copy the coverage files of the run just finished
  local dest="app/build/layers/$1"
  mkdir -p "$dest"
  find app/build/outputs/unit_test_code_coverage app/build/outputs/code_coverage \
    \( -name '*.exec' -o -name '*.ec' \) -exec cp {} "$dest/" \; 2>/dev/null || true
}

for layer in "${LAYERS[@]}"; do
  T0=$(stamp)
  rm -rf app/build/outputs/unit_test_code_coverage app/build/outputs/code_coverage
  case "$layer" in
    unit)
      "$GRADLE" :app:testDebugUnitTest --rerun-tasks -q ;;
    integration)
      "$GRADLE" :app:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.notPackage=dev.aoframe.e2e -q ;;
    e2e)
      "$GRADLE" :app:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.package=dev.aoframe.e2e -q ;;
    *) echo "Unknown layer: $layer" >&2; exit 2 ;;
  esac
  keep "$layer"
  echo "layer $layer: $(( $(stamp) - T0 )) s"
done

percent() {  # percent <xml>: line coverage from a JaCoCo XML report
  python - "$1" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
tail = text[text.rindex("</package>"):]
NAMES = {"LINE": "lines", "BRANCH": "branches"}
def pct(kind):
    missed, covered = map(int, re.search(rf'<counter type="{kind}" missed="(\d+)" covered="(\d+)"/>', tail).groups())
    return f"{100.0 * covered / (missed + covered):.1f} % {NAMES[kind]} ({covered}/{missed + covered})"
print(pct("LINE") + ", " + pct("BRANCH"))
PY
}

for layer in "${LAYERS[@]}"; do
  "$GRADLE" :app:jacocoMergedReport -Player="$layer" -q >/dev/null
  echo "coverage $layer: $(percent app/build/reports/jacoco/jacocoMergedReport/jacocoMergedReport.xml)"
done
"$GRADLE" :app:jacocoMergedReport -q >/dev/null
echo "coverage merged: $(percent app/build/reports/jacoco/jacocoMergedReport/jacocoMergedReport.xml)"
echo "total: $(( $(stamp) - START_ALL )) s"
