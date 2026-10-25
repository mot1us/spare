#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the isolated SDK}"
serial="${SPARE_TEST_SERIAL:-emulator-5580}"
adb="$ANDROID_HOME/platform-tools/adb"
mkdir -p app/build/reports/device
collect_reports() {
  local result="$1"
  if [ "$result" != 0 ]; then
    "$adb" -s "$serial" logcat -d -t 1500 > app/build/reports/device/logcat.txt 2>&1 || true
  fi
  for file in guide.png guide-all.png guide-preview.png source-save.png source-cancel.png failure.png failure.xml; do
    "$adb" -s "$serial" pull "/sdcard/Android/data/io.github.mot1us.spare.debug/files/$file" "app/build/reports/device/$file" >/dev/null 2>&1 || true
  done
  if [ -n "${RUNNER_TEMP:-}" ] && [ -f "$RUNNER_TEMP/emulator.log" ]; then
    cp "$RUNNER_TEMP/emulator.log" app/build/reports/device/emulator.log
  fi
}
trap 'collect_reports $?' EXIT
"$adb" -s "$serial" wait-for-device
for attempt in $(seq 1 120); do
  if [ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then break; fi
  sleep 1
done
test "$("$adb" -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" = 1
install_checked_apk() {
  local apk="$1" install_log="app/build/reports/device/install-$(basename "$1").txt"
  for attempt in 1 2 3; do
    "$adb" -s "$serial" wait-for-device
    if "$adb" -s "$serial" install -r "$apk" >"$install_log" 2>&1; then cat "$install_log"; return 0; fi
    cat "$install_log"
    # Package validation failures are definitive. Only transient transport/service
    # startup errors get a bounded installation retry; tests are never retried.
    if grep -q 'INSTALL_FAILED' "$install_log"; then return 1; fi
    sleep 2
  done
  return 1
}
install_checked_apk app/build/outputs/apk/debug/app-debug.apk
install_checked_apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb" -s "$serial" shell am instrument -w io.github.mot1us.spare.debug.test/androidx.test.runner.AndroidJUnitRunner | tee app/build/reports/device/results.txt
grep -q '^OK (12 tests)' app/build/reports/device/results.txt
