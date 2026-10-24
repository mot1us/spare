# Changelog

## 0.1.0 — 2026-10-24

- Initial native Android TV live-TV player with M3U/Xtream sources, XMLTV guide, preview, Favorites, groups, search, last-channel resume, and audio/subtitle controls.
- Add guarded frame-rate matching, encrypted source/cache storage, bounded imports, per-source HTTP consent, and verified in-app APK updates.
- Add versioned builds, tests, lint, release-signing automation, and a physical-device validation checklist.
- Make the tested API 36 baseline explicit across local and CI lint, and sign the exact optimized APK produced by build checks.
- Wait for guide focus and hold the remote key beyond the long-press threshold in device checks; retain failure diagnostics from CI.

Physical onn/Samsung frame-rate and provider compatibility remain to be validated before promoting the initial prerelease to stable.
