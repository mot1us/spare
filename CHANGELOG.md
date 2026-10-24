# Changelog

## 0.1.1 — 2026-10-24

- Refresh the guide with clearer programme hierarchy, quieter dark controls, rounded channel rows, and a persistent remote-control hint.
- Make dialog buttons, text fields, checkboxes, and source-type choices visibly focused; show only fields relevant to the selected source type.
- Keep passwords masked after configuring single-line TV text entry.
- Keep HTTPS addresses intact when enabling HTTP compatibility, display the connection type, and normalize pasted Xtream API suffixes.
- Load live channels even when the optional Xtream category endpoint fails. Explain HTTP, authentication, DNS, timeout, certificate, and response-format failures without showing credentials; offer Edit source and Retry.
- Let prerelease builds discover signed prerelease updates through GitHub Releases while stable builds retain the stable channel.
- Add source-diagnostic, release-discovery, remote-focus, HTTPS-preservation, and provider-category regression coverage.

## 0.1.0 — 2026-10-24

- Initial native Android TV live-TV player with M3U/Xtream sources, XMLTV guide, preview, Favorites, groups, search, last-channel resume, and audio/subtitle controls.
- Add guarded frame-rate matching, encrypted source/cache storage, bounded imports, per-source HTTP consent, and verified in-app APK updates.
- Add versioned builds, tests, lint, release-signing automation, and a physical-device validation checklist.
- Make the tested API 36 baseline explicit across local and CI lint, and sign the exact optimized APK produced by build checks.
- Wait for guide focus and hold the remote key beyond the long-press threshold in device checks; retain failure diagnostics from CI.
- Initialize the Android SDK explicitly in the independent APK-signing job.

Physical onn/Samsung frame-rate and provider compatibility remain to be validated before promoting the initial prerelease to stable.
