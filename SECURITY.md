# Security

Do not post playlist URLs, server credentials, signing material, or account details in issues, logs, or screenshots.

For a suspected security issue, use GitHub’s private vulnerability reporting if enabled on this repository. If it is unavailable, open an issue containing only a request for a private reporting channel; omit exploit details and secrets until one is established.

HTTPS cannot be replaced by an HTTP warning; per-playlist HTTP consent is an explicit compatibility tradeoff. Updater requests remain HTTPS-only. Android's package installer remains the final authority for APK signature/install validation.

The security contracts are split by boundary:

- [Android provider storage](docs/specs/android-provider-storage.md): authenticated encrypted files, provider identity, account edits and deletion.
- [Playback](docs/specs/playback.md): source-specific transport consent and missing-media classification.
- [Updates and release](docs/specs/updates-and-release.md): official metadata URLs, APK digest/package/signer checks, private installer sessions and trusted signing jobs.
- [macOS preview](docs/specs/macos.md): Keychain credentials, encrypted identity-bound snapshots, bounded metadata requests and preview limitations.

Mac VLC media redirects and HLS child requests currently bypass the app's per-playlist transport check. Mac artwork caching also lacks aggregate disk/decoded-memory bounds. These are documented limitations, not guarantees supplied by encrypted caches or download byte limits. The preview is ad-hoc signed and is not notarized.

Use synthetic fixtures for security and playback reports. Do not collect real provider credentials or reproduce private source URLs in test artifacts. Review local-only configuration only with the owner's explicit authorization, and keep signing keys, passwords and machine paths out of checked-in files. Production release signing belongs only in trusted main-branch jobs with protected secrets.
