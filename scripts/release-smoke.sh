#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the isolated SDK}"
: "${JAVA_HOME:?Set JAVA_HOME to the build JDK}"
serial="${SPARE_TEST_SERIAL:-emulator-5580}"
adb="$ANDROID_HOME/platform-tools/adb"
report=app/build/reports/release-smoke
mkdir -p "$report"
tmp=$(mktemp -d)
installed=false
cleanup() {
  if [ "$installed" = true ]; then
    if [ "$1" != 0 ]; then
      "$adb" -s "$serial" exec-out screencap -p > "$report/failure.png" || true
      "$adb" -s "$serial" logcat -d -t 1500 > "$report/logcat.txt" 2>&1 || true
    fi
    "$adb" -s "$serial" uninstall io.github.mot1us.spare >/dev/null || true
  fi
  rm -rf "$tmp"
}
trap 'cleanup $?' EXIT
"$adb" -s "$serial" wait-for-device
for attempt in $(seq 1 120); do
  if [ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then break; fi
  sleep 1
done
test "$("$adb" -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" = 1
"$JAVA_HOME/bin/keytool" -genkeypair -keystore "$tmp/debug.p12" -storetype PKCS12 -storepass android -keypass android \
  -alias androiddebugkey -dname 'CN=Android Debug,O=Spare CI,C=US' -keyalg RSA -keysize 2048 -validity 2 -noprompt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$tmp/debug.p12" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --v4-signing-enabled false --out "$tmp/spare-smoke.apk" app/build/outputs/apk/release/app-release-unsigned.apk
# A fresh install keeps the smoke separate from any previous release app and its data.
"$adb" -s "$serial" install "$tmp/spare-smoke.apk"
installed=true
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb" -s "$serial" shell am instrument -w -e releaseSmoke true io.github.mot1us.spare.debug.test/io.github.mot1us.spare.SpareTestRunner | tee "$report/results.txt"
grep -q '^INSTRUMENTATION_RESULT: releaseSmoke=passed' "$report/results.txt"
