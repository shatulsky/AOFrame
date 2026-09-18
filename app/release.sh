#!/usr/bin/env bash
# Builds AOFrame's release variant, installs it onto the frame over
# network ADB, and launches it.
#
# Signed with the debug keystore (see app/build.gradle.kts's release
# signingConfig) - a sideload-only device typically has no Play Store, so
# a dedicated release keystore buys nothing here, and
# matching the existing signature is what lets `adb install -r` upgrade
# in place without wiping app data (immich-secrets.json, the asset-cache
# DB/disk cache, night-mode/countdown config).
#
# Also (re-)provisions immich-secrets.json from the local copy at
# AOFrame/immich-secrets.json after installing, so this script doesn't
# need to fall back on recovering the API key from the Pi by hand every
# time a fresh install (a connectedDebugAndroidTest wipe, or a brand new
# checkout) loses it. Uses root (`su -c`), not `run-as` - the release
# build isn't debuggable, so `run-as` fails against it.
#
# Deliberately does NOT run the test suite - that's a separate, explicit
# step (./gradlew.bat testDebugUnitTest / connectedDebugAndroidTest).
# connectedDebugAndroidTest in particular uninstalls both the app and its
# test APK after the run, wiping app data in the process - this script is
# for getting a real build onto the frame and running it, not for
# verifying it.
#
# Usage: ./release.sh   (run from anywhere - paths are relative to this file)
#
# Reads the frame's ADB address from immich-secrets.json's "frameAddr"
# field (copy immich-secrets.example.json to immich-secrets.json and fill
# in your own values first) rather than hardcoding it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

SECRETS_FILE="immich-secrets.json"
if [ ! -f "$SECRETS_FILE" ]; then
	echo "$SECRETS_FILE not found - copy immich-secrets.example.json to $SECRETS_FILE and fill in your own values first." >&2
	exit 1
fi

FRAME_ADDR="$(node -e "const c=require('./$SECRETS_FILE'); if(!c.frameAddr) throw new Error('missing frameAddr'); console.log(c.frameAddr)")"
PACKAGE="dev.aoframe"
ACTIVITY="$PACKAGE/.MainActivity"

# No separate JDK install on this dev machine - reuse Android Studio's
# bundled JBR, same as every other build command in this repo's history.
# Respects an already-exported JAVA_HOME so this still works on a
# different dev machine.
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"

echo "Building release APK..."
./gradlew.bat :app:assembleRelease --console=plain

APK="$(find app/build/outputs/apk/release -name '*.apk' | head -n1)"
if [ -z "$APK" ]; then
	echo "No release APK found under app/build/outputs/apk/release" >&2
	exit 1
fi

echo "Connecting to the frame ($FRAME_ADDR)..."
adb connect "$FRAME_ADDR" >/dev/null

echo "Installing $APK..."
adb -s "$FRAME_ADDR" install -r "$APK"

echo "Provisioning $SECRETS_FILE..."
MSYS_NO_PATHCONV=1 adb -s "$FRAME_ADDR" push "$SECRETS_FILE" /data/local/tmp/immich-secrets.json >/dev/null
APP_UID="$(MSYS_NO_PATHCONV=1 adb -s "$FRAME_ADDR" shell "su -c 'stat -c %U /data/data/$PACKAGE'" | tr -d '\r')"
MSYS_NO_PATHCONV=1 adb -s "$FRAME_ADDR" shell "su -c '
	mkdir -p /data/data/$PACKAGE/files &&
	chown $APP_UID:$APP_UID /data/data/$PACKAGE/files &&
	chmod 771 /data/data/$PACKAGE/files &&
	cp /data/local/tmp/immich-secrets.json /data/data/$PACKAGE/files/immich-secrets.json &&
	chown $APP_UID:$APP_UID /data/data/$PACKAGE/files/immich-secrets.json &&
	chmod 660 /data/data/$PACKAGE/files/immich-secrets.json &&
	rm /data/local/tmp/immich-secrets.json
'"

echo "Force-stopping Frameo (safety-net launcher) and launching $PACKAGE..."
adb -s "$FRAME_ADDR" shell am force-stop net.frameo.frame
adb -s "$FRAME_ADDR" shell am start -n "$ACTIVITY"

echo "Done - $PACKAGE installed and launched on the frame (ADB connection left open)."
