# Releasing Spare

`version.properties` is the native version source. Gradle generates `versionName`, `versionCode`, and BuildConfig from it. Change both values for a completed update, update CHANGELOG, run required checks, and include the version in the delivery commit. Preserve existing releases and tags.

## One-time signing setup

The owner must retain the release keystore and password in encrypted backup storage. They are not part of this repository. Add repository Actions secrets:

- `SPARE_KEYSTORE_BASE64`: base64-encoded PKCS12 keystore.
- `SPARE_KEYSTORE_PASSWORD`: keystore/key password.

The keystore alias is `spare`. The owner configures these in the repository's GitHub Actions settings. Do not send secrets through chat or commits.

Release signing is permitted only in trusted main-branch jobs with protected secrets. Do not supply production signing material to local development or agent builds. The build tooling recognizes `SPARE_KEYSTORE`, `SPARE_STORE_PASSWORD`, `SPARE_KEY_PASSWORD`, and `SPARE_KEY_ALIAS`; their presence is not authorization to sign outside that boundary. Missing signing configuration produces an **unsigned** APK, never a debug-signed production APK. Isolated installer tests use disposable test signing identities.

## CI and release flow

The Checks workflow runs Android builds, unit tests and lint, version/tooling checks, and Mac Swift tests on main-branch pushes and pull requests. New commits cancel obsolete Checks runs. It does not boot a TV emulator or publish APKs. The [updater and release contract](specs/updates-and-release.md) defines artifact validation and installation invariants.

Request a release only after implementation is ready: update `release-request.json` to match `version.properties` and increment its `attempt`, then commit and push it to main. That marker triggers Publish APK, which retains the full build, isolated TV, native update-install, signing and manifest verification gates. Routine UI requests set `tvSuite` to `focused` and list relevant method names in `tvTests`; three mandatory smoke checks are always included. Playback-engine, persistence/encryption and updater changes use `tvSuite: full`. A weekly run and the manual Full TV option also exercise the entire suite without requiring publication. A failed gate requires inspecting and fixing its cause before a new attempt. Code-only correction commits run Checks; increment the request attempt when ready to publish again. A manual main-branch Publish APK dispatch with publish enabled is also supported. Pull requests never receive signing secrets.

The marker may keep the last requested version while the tracked native version advances for unreleased configuration/documentation work. Mismatched versions skip publication. Never advance the marker for intermediate commits.

`releaseChannel=prerelease` keeps the initial version outside stable updates until the physical checklist passes. After that checkpoint, a new versioned update can set `releaseChannel=stable`; subsequent explicitly requested, tested updates then publish into the in-app stable update channel. Existing release tags and artifacts are not replaced. The manual workflow can force a prerelease but cannot bypass the tracked prerelease channel.

The workflow publishes `spare.apk`, `SHA256SUMS`, and `update.json` at `v<versionName>`. Existing tags/releases are not overwritten. The metadata pins the APK URL to its exact version; the app also checks the APK’s package, higher version code, and signing identity. Android still verifies the package at installation.

Before publication, `scripts/release_tag.py` checks the remote tag and rejects an existing tag even when it has no GitHub Release. This prevents attaching new binaries to an older source commit. A failed remote lookup or a lookup exceeding 60 seconds also fails the gate; only an explicit missing tag permits publication. Run `python3 -m unittest discover -s scripts/tests` for the local tooling regressions.

Before publishing, the release script verifies the signed APK certificate against `release-certificate.sha256`, and verifies its native package/version against `version.properties`. That public fingerprint is safe to commit; the private keystore and password are not.

The first APK must be installed once by sideloading. Stable update discovery uses `releases/latest/download/update.json`. Prereleases do not enter that channel. Current builds with `releaseChannel=prerelease` try the repository's Atom release feed first and fall back to the public Releases API; both paths accept only exact version-pinned manifest URLs in this repository. Stable builds keep their original channel. Version 0.1.0 requires one manual installation to obtain testing-channel discovery, introduced in 0.1.1. Create the AFTVnews Downloader shortcut to `https://github.com/mot1us/spare/releases/latest/download/spare.apk` only after a stable APK exists. Keep the asset name `spare.apk` across releases so the shortcut remains useful.

## Optional Mac preview

Set `macos: true` on an intentional release request, or enable the manual Mac option, to run the Mac build and attach its artifacts after the APK release succeeds. The default request can leave Mac publication disabled. The Mac job runs Swift tests and `scripts/macos-build.py`, which derives bundle versions from `version.properties`, packages a universal app with the checksum-pinned VLC runtime and corresponding source, and verifies ad-hoc signing and architectures. It emits a versioned ZIP, VLC source archive and `MACOS-SHA256SUMS` under `dist/macos/`.

Mac attachment requires both the Mac and APK jobs to succeed; a Mac failure does not independently block APK publication. Existing Mac assets are not replaced. The preview is not notarized and has no in-app Mac installer. Perform the synthetic native smoke checks in [Testing](TESTING.md) before requesting distribution; the workflow's Swift tests and packaging checks do not exercise native video rendering.
