# Spare project map

## Entry points and contracts

- `app/src/main/java/io/github/mot1us/spare/`: native Android TV app. `MainActivity` owns the primary player and screen lifecycle; `SecureStore` owns encrypted persistence; `LibraryCatalog` owns bounded catalog pages and shared import jobs.
- `app/src/test/`: JVM regression tests. `app/src/androidTest/`: synthetic network/media fixtures and isolated Android TV scenarios.
- `docs/README.md`: documentation entry point. `docs/specs/` covers architecture, provider ingestion/storage, guide/navigation, playback, Library/Search, Sports, and updates/release. `docs/TESTING.md` and `docs/RELEASING.md` define current workflows; `docs/TESTING-HISTORY.md` preserves superseded release-specific checks and counts.
- `version.properties` supplies the version. Every completed update increments semantic version and monotonic code, with a matching `CHANGELOG.md` entry and versioned delivery commit.

## Verification and release

- Android: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`. Supply an SDK through `ANDROID_HOME`; do not inspect or commit private local configuration.
- Tooling: `python3 scripts/version.py` and `python3 -m unittest discover -s scripts/tests`.
- Device: use a fresh account-free Android TV emulator. `SPARE_TV_SUITE=full bash scripts/device-test.sh` streams each test; the selector discovers methods dynamically. Also use `scripts/update-device-test.sh` and `scripts/release-smoke.sh` for installer/minified verification. Never point these scripts at physical or personal devices.
- Build output belongs in ignored `build/`, `.build/`, and `dist/` directories. Do not commit generated artifacts or signing material.
- `release-request.json` is a publication request, not the current project version. Leave its last-requested version unchanged when delivery does not request publication. An intentional new release must match the current native version and complete all gates. Existing tags are rejected.
