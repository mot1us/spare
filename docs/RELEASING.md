# Releasing Spare

`version.properties` is the native version source. Gradle generates `versionName`, `versionCode`, and BuildConfig from it. Change both values for a completed update, update CHANGELOG, run required checks, and include the version in the delivery commit. Preserve existing releases and tags.

## One-time signing setup

The owner must retain the release keystore and password in encrypted backup storage. They are not part of this repository. Add repository Actions secrets:

- `SPARE_KEYSTORE_BASE64`: base64-encoded PKCS12 keystore.
- `SPARE_KEYSTORE_PASSWORD`: keystore/key password.

The keystore alias is `spare`. The connector does not expose a repository-secret creation tool; the owner sets these through GitHub settings. Do not send secrets through chat or commits.

For local release builds, supply `SPARE_KEYSTORE`, `SPARE_STORE_PASSWORD`, `SPARE_KEY_PASSWORD`, and `SPARE_KEY_ALIAS=spare` in a private environment. Missing signing configuration produces an **unsigned** APK, never a debug-signed production APK.

## CI and release flow

Build checks run on pushes and pull requests. A trusted push to `main` automatically publishes a signed release only after build and emulator checks pass. A manual main-branch workflow dispatch can also request publication. Pull requests never receive signing secrets.

`releaseChannel=prerelease` keeps the initial version outside stable updates until the physical checklist passes. After that checkpoint, a new versioned update can set `releaseChannel=stable`; subsequent tested main-branch updates then publish into the in-app stable update channel automatically. Existing release tags and artifacts are not replaced. The manual workflow can force a prerelease but cannot bypass the tracked prerelease channel.

The workflow publishes `spare.apk`, `SHA256SUMS`, and `update.json` at `v<versionName>`. Existing tags/releases are not overwritten. The metadata pins the APK URL to its exact version; the app also checks the APK’s package, higher version code, and signing identity. Android still verifies the package at installation.

Before publishing, the release script verifies the signed APK certificate against `release-certificate.sha256`, and verifies its native package/version against `version.properties`. That public fingerprint is safe to commit; the private keystore and password are not.

The first APK must be installed once by sideloading. Stable update discovery uses `releases/latest/download/update.json`. Prereleases do not enter that channel. From 0.1.1, builds with `releaseChannel=prerelease` discover testing updates through the public Releases API and accept only exact version-pinned manifest URLs in this repository; stable builds keep their original channel. Version 0.1.0 requires one manual installation to obtain this correction. Create the AFTVnews Downloader shortcut to `https://github.com/mot1us/spare/releases/latest/download/spare.apk` only after a stable APK exists. Keep the asset name `spare.apk` across releases so the shortcut remains useful.
