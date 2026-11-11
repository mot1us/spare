# Spare project map

## Entry points and contracts

- `app/src/main/java/io/github/mot1us/spare/`: native Android TV app. `MainActivity` owns the primary player and screen lifecycle; `SecureStore` owns encrypted persistence; `LibraryCatalog` owns bounded catalog pages and shared import jobs.
- `app/src/test/`: JVM regression tests. `app/src/androidTest/`: synthetic network/media fixtures and isolated Android TV scenarios.
- `macos/Sources/SpareCore/`: import, HTTP, encrypted Vault, and Sports services. `macos/Sources/SpareMac/`: SwiftUI state/views and the serial VLC player worker. `macos/Sources/CSpare/`: dynamically loaded VLC bridge.
- `docs/specs/`: domain contracts for Android provider storage, playback, and the macOS preview. `docs/TESTING.md` and `docs/RELEASING.md` define validation and release workflows.
- `version.properties` supplies both platform versions. Every completed update increments semantic version and monotonic code, with a matching `CHANGELOG.md` entry and versioned delivery commit.

## Verification and release

- Android: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`. Supply an SDK through `ANDROID_HOME`; do not inspect or commit private local configuration.
- Tooling: `python3 scripts/version.py` and `python3 -m unittest discover -s scripts/tests`.
- Device: use a fresh account-free Android TV emulator. `SPARE_TV_SUITE=full bash scripts/device-test.sh` streams each test; the selector discovers methods dynamically. Also use `scripts/update-device-test.sh` and `scripts/release-smoke.sh` for installer/minified verification. Never point these scripts at physical or personal devices.
- Mac: run `swift test` in `macos/`, then `python3 scripts/macos-build.py` from the root. The build downloads checksum-pinned VLC artifacts and verifies both architectures and ad-hoc signing. `--demo` uses a synthetic playlist on localhost port 8767; set `SPARE_DATA_DIR` to an isolated directory. Demo secrets are in memory.
- Build output belongs in ignored `build/`, `.build/`, and `dist/` directories. Do not commit generated artifacts or signing material.
- `release-request.json` is a publication request, not the current project version. Leave its last-requested version unchanged when delivery does not request publication. An intentional new release must match the current native version and complete all gates. Existing tags are rejected.
