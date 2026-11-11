# Updates and release contract

## Purpose and scope

Spare distributes Android APKs and the optional macOS preview through this repository's GitHub Releases. There is no Spare account, hosted credential service or VPS dependency. This contract covers the Android updater's trust boundary and the automation that produces release artifacts. [Releasing](../RELEASING.md) is the operator workflow; [Testing](../TESTING.md) contains build, emulator and physical-device checks.

## Ownership and public data

| Component | Responsibility |
| --- | --- |
| `Release` / `UpdateLookup` | Select an official manifest and validate its version, package URL and digest fields. |
| `Updater` | Check availability, obtain user confirmation, download privately, and validate the APK before staging installation. |
| `UpdateInstallActivity` / `UpdateInstallReceiver` | Commit the verified file through Android PackageInstaller and recover status across process replacement. |
| `version.properties` / `scripts/version.py` | Supply the native semantic version, Android version code and update channel. |
| `scripts/release_manifest.py` / `scripts/release_tag.py` | Verify the built artifact and signing fingerprint, produce metadata, and reject reused remote tags. |
| `.github/workflows/build.yml` / `release.yml` | Separate ordinary checks from requested release verification, signing and publication. |

`update.json` contains `versionName`, `versionCode`, `apkUrl` and `sha256`. The version has three numeric components; the code is in `1..2,100,000,000`; the digest is exactly 64 lowercase hexadecimal characters. The APK URL must exactly equal `https://github.com/mot1us/spare/releases/download/v<versionName>/spare.apk`. A different host, repository, path, tag or asset name is rejected.

The manifest's digest provides integrity against the selected metadata, not an independent signature over that metadata. APK signer equality with the installed app supplies the additional signing-identity check. Android's package installer remains the final installation authority.

## Discovery and checks

Stable builds read `releases/latest/download/update.json`. Prerelease builds first try this repository's Atom release feed, then fall back to its public Releases API if feed discovery or the selected manifest fails. Testing discovery accepts exact `vMAJOR.MINOR.PATCH` tags, ignores API drafts, requires version-pinned manifest addresses and chooses the highest semantic version among the bounded candidates. It can include stable releases as well as prereleases. A pinned manifest must report the version named in its own URL.

All updater requests require HTTPS, including redirects; provider HTTP consent cannot relax this. Feed/API responses are limited to 256 KiB and manifests to 16 KiB. Atom parsing rejects DTDs and unsupported entities, with depth and element limits. Discovery opens no player or provider connection.

Automatic updates are disabled in debug builds. A release build checks once per startup when requested by the activity; other automatic checks are throttled to 24 hours after a successful check. Manual checks bypass that throttle. Concurrent checks coalesce through the updater's busy state. Only a version code above the installed code is offered. Offers wait for the foreground, and installation starts only after the user chooses Install now. Manual failures show safe network/verification messages; failed checks do not advance the last-success timestamp.

## Download and installation invariants

- Downloads stream to a private temporary file with a 96 MiB transport limit. The final `updates/spare.apk` path is published only after digest and package verification succeed. Partial or rejected staged files are removed.
- The archive must have Spare's installed package name, the manifest's exact version name/code, a strictly newer code, and the same nonempty set of current signing certificates as the installed app. A valid hash alone is insufficient.
- Signing-key rotation is not currently accepted through a lineage policy: the comparison is exact current-signer equality. Changing that policy requires a separate reviewed migration.
- Android may require unknown-app installation permission or a confirmation screen. Requesting installation without further user action does not guarantee silent installation on every device.
- The installer activity and status receiver are private components. A persisted session ID is written before commit; only the matching session's status is accepted. Interrupted updates abandon only the app's own unfinished sessions when staging a replacement.
- Success clears session state and cached APK bytes. Failure/cancellation leaves the installed app intact and reports a recoverable result. Android can kill Spare during replacement, so automatic reopening is not guaranteed; the user can reopen it from the launcher.

## Version and publication invariants

Every completed project update increments the semantic version and monotonically increasing code, adds a changelog entry, and uses the delivered version in its commit. Android and macOS derive native versions from the same file. The version script validates shape/range and a matching changelog heading; reviewers and delivery history must still establish monotonicity. Dependency locks remain synchronized with actual dependency changes.

`release-request.json` is an explicit publication marker, not another version authority. Push-triggered publication requires its name/code to match the tracked version. It may retain an earlier requested version during unreleased work. Its attempt counter is advanced when requesting another run; a manual workflow dispatch has explicit publish/full-TV/Mac controls. Weekly runs verify the full TV suite without publishing.

Ordinary Checks run Android unit tests, lint, debug/release/test APK builds, version/tooling checks and Mac Swift tests. They do not start a TV emulator or publish. Release verification additionally gates on isolated TV scenarios, the native update-install exercise and a minified-release smoke test. Focused requests always include startup, playback and encrypted-storage smoke checks; unknown test names fail selection. Playback-engine, storage/security and updater changes require the full suite. Physical-device behavior and refresh-rate/provider claims still require the hardware checklist.

Release signing is permitted only in trusted main-branch jobs with protected secrets. Pull requests and ordinary development builds must not receive production signing material. Missing signing configuration yields an unsigned release APK, never a debug-signed production artifact. Test signing identities used by isolated installer checks are not distribution keys.

The release job signs the already checked optimized APK, verifies the public certificate fingerprint and native package/version, then produces `spare.apk`, `SHA256SUMS` and `update.json`. Release notes come from the matching changelog section. An existing tag is rejected even if no GitHub Release exists; failed or timed-out remote lookup also fails closed. A new release targets the checked commit. Published tags and artifacts must not be rewritten.

The tracked prerelease channel cannot be overridden to stable by a manual run. A manual request may force prerelease. Moving the tracked channel to stable requires the physical checkpoint described in the release workflow, so prerelease testing does not silently move users onto the stable channel.

## macOS distribution

The Mac preview uses manual GitHub downloads rather than the Android installer. `scripts/macos-build.py` defaults to a universal app, downloads checksum-pinned VLC runtime/source artifacts, bundles the dynamic runtime and licenses, applies ad-hoc signing, verifies signatures and requested architectures, and emits a versioned ZIP plus source archive and `MACOS-SHA256SUMS`. Ad-hoc signing is not Developer ID notarization. The development-only local-runtime option relies on an already verified input and is not equivalent to downloading the pinned runtime bytes.

Mac publication is opt-in. Its build and Swift tests run separately; appending Mac assets requires both that job and the APK release job to succeed. Mac build failure does not by itself block the APK job, but prevents Mac attachment. Uploading uses no replacement option. See [Mac contracts](macos.md) for runtime transport and resource limitations.

## Validation and remaining boundaries

`ReleaseTest`, `UpdateLookupTest` and `UpdateDownloadTest` cover official addresses, version consistency, discovery fallback and rejected/partial downloads. `scripts/tests/test_release_safety.py` covers reused tags, failed tag lookup and physical-device rejection. The isolated update-device script stages the installed debug APK directly and verifies PackageInstaller self-replacement, status cleanup and reopening. It bypasses discovery/download verification and does not prove rejection of a wrong signer or package; those remain part of the physical signed-update checklist. JVM download tests inject package validation and cannot establish device installer policy or real archive-signer inspection. Release scripts are operational gates, not permission to publish during an ordinary documentation or implementation task.

## Change notes

- 2026-11-10: Established the updater and distribution contract from existing code and workflows; corrected the operator description of feed-first prerelease discovery and trusted-main-only release signing.
