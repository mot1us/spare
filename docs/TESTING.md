# Build and testing

## Prerequisites and local boundaries

Android uses JDK 17, the checked-in Gradle wrapper, and an Android SDK with
platform 36 and the build tools selected by Gradle. Set `ANDROID_HOME` to an SDK
available to the build. The optimized-release smoke script also requires
`JAVA_HOME` and SDK build-tools 35.0.0 for `apksigner`. Device tests additionally need platform-tools, an emulator,
and an Android TV system image. CI configuration is in
[build.yml](../.github/workflows/build.yml) and
[release.yml](../.github/workflows/release.yml).

macOS requires a Mac with Xcode command-line tools and Swift 5.9 or newer; the
package targets macOS 13+. Native packaging also needs access to the pinned VLC
runtime/source downloads unless already cached.

Use synthetic playlists, guide data, credentials, and media. Do not inspect
private `.env`, credential, or machine-specific configuration files to find build
settings. Do not commit SDK paths, signing material, or generated build output.
A restricted shell may need approved access to Gradle/compiler caches; this is
separate from a missing SDK or a compilation failure.

## Required build checks

Run from the repository root:

```sh
python3 scripts/version.py
python3 -m unittest discover -s scripts/tests
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

The version script validates semantic format, channel, and the matching changelog
heading. It does not compare against earlier commits: review the diff to verify
that `versionCode` increases. Local release assembly is unsigned when production
signing inputs are absent. Production signing belongs only to trusted main-branch
jobs, as described in [Releasing](RELEASING.md).

Android unit tests live in `app/src/test/`; instrumentation and fixture services
live in `app/src/androidTest/`. The test APK downloads one checksum-pinned Media3
transport-stream fixture during assembly. That fixture is not a release asset.
Gradle reports are under `app/build/reports/` and APKs under
`app/build/outputs/apk/`. Dependencies are locked; update locks only with an
intentional dependency change.

For macOS:

```sh
cd macos
swift test
cd ..
python3 scripts/macos-build.py
```

Swift tests cover core parsing/storage/services and app-state/player-watchdog
contracts. The packaging script builds both architectures by default, bundles the
separate VLC runtime and license/source material, checks executable/VLC
architectures, declares a macOS 13 minimum, and verifies ad-hoc signing. Packaging is distinct from
real native UI/playback verification.

## Isolated Android TV tests

Changes to startup, playback, storage, guide, focus, or remote behavior require
relevant device coverage. Create an account-free Android TV emulator in an
isolated `ANDROID_AVD_HOME`; do not reuse a personal emulator. Never point the
mutating test scripts at a physical device. `SPARE_TEST_SERIAL` must be an
`emulator-<port>` transport; that serial check does not prove data isolation.

After building both debug APKs and starting the disposable emulator:

```sh
SPARE_TEST_SERIAL=emulator-5580 SPARE_TV_SUITE=full bash scripts/device-test.sh
```

Routine UI release requests use `tvSuite: focused` plus the relevant `tvTests`
method names in `release-request.json`. To run that selection locally:

```sh
python3 scripts/tv_tests.py --suite focused
SPARE_TEST_SERIAL=emulator-5580 SPARE_TV_SUITE=focused bash scripts/device-test.sh
```

Focused selection always includes these smoke tests:

- `encryptedStorageAuthenticatesDataAndNeverStoresPlaintext`
- `networkImportPreviewRemoteFavoritesAndResume`
- `cachedStartupDoesNotDownloadVodOrSweepUnvisitedChannels`

The selector rejects unknown tests and discovers the full count from current
source; do not use a historical count as the gate. Use full coverage for playback
engine, storage/security, or updater changes. Full coverage also runs weekly and
on manual request. The harness streams each active test and writes results,
screenshots, and failure diagnostics to `app/build/reports/device/`.

Preserve the native installer and minified-player gates for releases:

```sh
SPARE_TEST_SERIAL=emulator-5580 bash scripts/update-device-test.sh
SPARE_TEST_SERIAL=emulator-5580 bash scripts/release-smoke.sh
```

The installer smoke stages the installed debug APK, expects instrumentation to
stop during package replacement, verifies update time and staged-file/session
cleanup, then reopens the launcher. It does not exercise discovery, network
fetch, hash validation, or APK identity rejection. Those require their unit tests
and the signed physical update check below. Release smoke signs a temporary test
copy of the optimized APK and exercises synthetic import/playback; it does not use
or establish the production signing identity.

Scale local device coverage to the changed behavior. Once relevant checks pass,
do not rerun unrelated suites without a new change or unresolved failure. Record
any failed check and targeted rerun separately; never report a partial run as a
full fresh pass.

## Native Mac smoke

Use an isolated `SPARE_DATA_DIR` and `--demo` with the packaged executable:

```sh
SPARE_DATA_DIR="$(mktemp -d)" dist/macos/Spare.app/Contents/MacOS/Spare --demo
```

Demo mode uses in-memory secrets and a separate demo preference suite. It expects
a synthetic M3U server at `http://127.0.0.1:8767/playlist.m3u`; serve only generated
fixture channels and media, never real provider addresses. The demo preferences
are separate from normal settings but are shared between demo launches.

Check launch, channel selection, decoded video, pause/resume, preview/full-screen
transitions, Live TV/Sports navigation, and clean exit. Use at least two fixture
channels to exercise switching. An isolated data directory alone does not enable
in-memory secrets; keep `--demo`. Browser testing, if needed, uses an isolated
browser/profile without personal accounts.

## Physical onn 4K Pro and TV acceptance

Synthetic tests cannot prove real provider behavior or hardware performance.
Keep physical validation manual and record the app version, firmware/Android
version, TV model, HDMI path, and results without provider credentials or URLs.
Use the current [domain specs](README.md) when older test notes disagree.

1. Add authorized M3U and Xtream sources using the remote and paired keyboard.
   Verify source-edit Save/Cancel, HTTPS preservation, and explicit HTTP consent.
2. Verify usable cached startup offline, channel/category order, Favorites, Local
   regions, delayed/absent EPG, and refresh retaining playback and focus.
3. Exercise actual D-pad repeats, deep categories, the hidden/visible dock,
   guide preview/full screen, short/held Back, playback details, and previous-channel
   recall. Moving focus or changing appearance must not retune.
4. Exercise microphone permission, denial, unavailable recognition, typed fallback,
   and leaving Search while listening. The OS owns the remote microphone button.
5. Check audio/subtitle selection, embedded captions, and per-channel preferences.
   Compare provider-reported and observed FPS without treating labels as proof.
6. Test available 23.976/24, 25/50, 29.97/30, and 59.94/60 sources. Observe actual
   output timing on the TV signal display and record blackouts/unsupported modes.
7. Observe short and sustained network stalls, bounded recovery, missing endpoints,
   and missing HLS children. Separate stream startup from HDMI-switch blackout.
8. Verify real Sports schedule/coverage, uncertain broadcaster choices, completed
   scores/spoilers, score delay, reminders, overlay, and provider archive expiry.
9. Play movies/episodes and provider catch-up; verify redirect consent, seek/resume,
   Continue watching, Watchlist, source isolation, and the correct Back destination.
10. Test two-up/four-up with the account's allowed connection count. Select pane
    audio, add/remove channels, return to single playback, and background the app.
    Verify extra connections close and one failed pane leaves others usable.
11. Install a real signed higher-version update selected explicitly in Spare.
    Verify Android consent/unknown-source flow, data retention, launcher reopening,
    and rejection of wrong package/signer/hash or downgrades in controlled fixtures.
12. Background and reopen the app; audio must stop over the launcher/other apps.
    Measure cold launch, guide navigation, first frame, and rebuffering separately.

[Historical device notes](TESTING-HISTORY.md) retain older release-specific
scenarios and reported checkpoints. They are evidence/history, not proof that
these physical checks have passed for the current version.
