# Changelog

## 0.2.1 — 2026-10-24

- Separate startup, buffering, alternate-format, and reconnect status. Add a delayed corner buffering indicator, a larger resume cushion, a bounded buffer target, a conservative adaptive-live offset, and bounded reconnects.
- Stop retrying terminal HTTP errors. Temporarily hide channels only when their attempted startup endpoints all return 404/410; retain network/account failures and restore hidden channels after 15 minutes or on demand. Return to the last working channel when available.
- Put general controls above the guide and order USA categories first. Preserve the active category when returning to the sidebar.
- Offer transparent overlay and small live preview in Settings. Keep one player running while browsing; hold Back or click the current channel to expand without reconnecting.
- Replace the sports preset and 60-only toggle with FPS exclusions, grouping fractional broadcast rates with their nominal rates. Preserve existing 60-only preferences during migration.
- Load bounded per-channel Xtream EPG for visible channels and the current stream instead of requiring a whole-provider XMLTV download. Decode programme text, handle missing EPG IDs, cache data, and explain guide failures. Accept additional XMLTV time formats without relaxing entity or size protections.
- Extend regression coverage for actual network stalls, 404 versus account failures, preview rendering, hold-Back, guide modes, category ordering, FPS exclusions, and EPG responses.

## 0.2.0 — 2026-10-24

- Add a compact translucent guide over live video, provider categories and Favorites on the left, smaller text, separated controls, and Back-to-guide navigation.
- Add per-source category, HD/FHD/4K minimum-resolution, and 59.94/60 fps filters, plus an MLB/NFL FHD+60 preset. Quality is observed during playback; unknown streams stay hidden under quality filters. Browse all temporarily bypasses preferences for verification without background scanning.
- Enable MPEG-TS access-unit detection and non-IDR startup compatibility, decoder fallback, consent-aware cross-protocol stream redirects, Xtream TS/HLS fallback, and an explicit stream-format setting. Replace endless startup with a timeout and safe playback diagnostics.
- Show playlist/guide import stages and measured download progress. Preserve one player while browsing and retain per-source quality observations locally.
- Extend unit and isolated Android TV coverage for quality filters, encoded stream URLs, MPEG-TS without access-unit delimiters, HLS fallback, progress, categories, and remote navigation.

## 0.1.2 — 2026-10-24

- Fix large provider imports: replace the 20,000-entry rejection with a bounded 100,000-entry channel limit, and explain that limit explicitly when exceeded.
- Skip malformed Xtream entries while preserving usable channels and provider order.
- Preserve the entered server URL, including its trailing slash, in source settings; normalize only when constructing requests.
- Give remaining data, interrupted-connection, and HTTP-protocol failures distinct reasons without exposing provider URLs or credentials.
- Add regression coverage for large lists, malformed entries, slash handling, and a full 25,001-channel import through video playback on Android TV.

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
