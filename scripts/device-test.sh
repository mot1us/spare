#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the isolated SDK}"
serial="${SPARE_TEST_SERIAL:-emulator-5580}"
adb="$ANDROID_HOME/platform-tools/adb"
"$adb" -s "$serial" wait-for-device
for attempt in $(seq 1 120); do
  if [ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then break; fi
  sleep 1
done
test "$("$adb" -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" = 1
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p app/build/reports/device
"$adb" -s "$serial" shell am instrument -w io.github.mot1us.spare.debug.test/androidx.test.runner.AndroidJUnitRunner | tee app/build/reports/device/results.txt
grep -q '^OK (3 tests)' app/build/reports/device/results.txt
"$adb" -s "$serial" pull /sdcard/Android/data/io.github.mot1us.spare.debug/files/guide.png app/build/reports/device/guide.png
"$adb" -s "$serial" pull /sdcard/Android/data/io.github.mot1us.spare.debug/files/guide-all.png app/build/reports/device/guide-all.png
