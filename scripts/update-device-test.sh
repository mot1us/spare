#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the isolated SDK}"
serial="${SPARE_TEST_SERIAL:-emulator-5580}"
[[ "$serial" == emulator-* ]] || { echo 'Use an isolated emulator for the self-install test'; exit 1; }
adb="$ANDROID_HOME/platform-tools/adb"
package=io.github.mot1us.spare.debug
mkdir -p app/build/reports/device
trap '"$adb" -s "$serial" shell appops set "$package" REQUEST_INSTALL_PACKAGES default >/dev/null 2>&1 || true' EXIT
updated_at() { "$adb" -s "$serial" shell dumpsys package "$package" | sed -n 's/.*lastUpdateTime=//p' | head -n 1 | tr -d '\r'; }
before="$(updated_at)"
"$adb" -s "$serial" shell appops set "$package" REQUEST_INSTALL_PACKAGES allow
# Successful self-replacement kills this instrumentation. Verify the actual install,
# rather than treating that expected process termination as a test result.
"$adb" -s "$serial" shell am instrument -w -e installSmoke true "$package.test/io.github.mot1us.spare.SpareTestRunner" > app/build/reports/device/update-session.txt 2>&1 || true
for attempt in $(seq 1 30); do
  after="$(updated_at)"
  if [ -n "$after" ] && [ "$after" != "$before" ] &&
      "$adb" -s "$serial" shell run-as "$package" test ! -e cache/updates/spare.apk &&
      ! "$adb" -s "$serial" shell run-as "$package" cat shared_prefs/update-install.xml | grep -q 'name="session"'; then
    "$adb" -s "$serial" shell am start -W -n "$package/io.github.mot1us.spare.MainActivity" > app/build/reports/device/update-reopen.txt
    echo 'Native self-update installed, status received, staging cleaned, and Spare reopened.'
    exit 0
  fi
  sleep 1
done
"$adb" -s "$serial" logcat -d -t 1200 > app/build/reports/device/update-logcat.txt
echo 'Native self-update did not complete cleanly.'
exit 1
