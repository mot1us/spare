# Changelog

## 0.9.2 — 2026-10-26

- Right from the dock returns to current content; only OK opens a destination. Add hidden or always-open navigation with a smooth overlay that keeps content layout steady.
- Give Sports Scores the full screen area; keep the live preview when finding and watching streams.
- Keep Library category focus and loaded poster pages stable during imports, reject stale category callbacks, and make manual refresh apply once.

## 0.9.1 — 2026-10-26

- Replace mixed controls with a uniform, fixed navigation rail: Search, Guide, Sports, Library, Settings. Preserve the selected category when moving between columns.
- Keep Settings open while changing options and make Left move one level per press. Settings also owns input when Library is underneath.
- Use one continuously retargeted scroll controller and retain nearby rows to reduce fast-scroll stutter and focus flashing.
- Back from per-channel Catch-up returns directly to the live channel without reopening an already playing stream.

## 0.9.0 — 2026-10-25

- Load and cache VOD by category instead of downloading the entire library before browsing.
- Separate Sports Scores and Watch, with a team sidebar, inline stream matches, sharper logos and optional two-stream multiview.
- Find FHD/60+ across the playlist without stale search/category restrictions; align resolution filtering with displayed video height.
- Wrap live channel lists, hold focus while offscreen rows attach, jump directly from categories to main controls, and add Glass, Flat and Grid styles.
- Add subtitle size/style controls with a smaller default; increase playback buffer headroom and wait longer before reconnecting a stalled stream.

## 0.8.5 — 2026-10-25

- Separate fast push/PR checks from explicitly requested signed releases. Cancel obsolete check runs and retain TV/update-install verification once a release is requested. The public APK remains 0.8.4 until the next release request.

## 0.8.4 — 2026-10-25

- Resume the saved channel when its FPS needs rechecking after a quality-cache migration, so strict FHD/FPS filters cannot prevent verification. Keep category, resolution and unavailable-channel restrictions intact.

## 0.8.3 — 2026-10-25

- Read AVC SPS timing and decoder headers; version old quality readings and synchronize live quality updates so details and FHD/FPS filters agree. Preserve progressive frames versus interlaced field-rate labels.
- Stream VOD catalogs into bounded encrypted pages with a small local index, show the first page before the full download, and keep imports independent of browsing and playback.
- Preserve Library focus and category positions, append pages without clearing the grid, and deliver provider details before optional metadata enrichment.

## 0.8.2 — 2026-10-25

- Measure timestamp cadence over a stable sample window instead of one median interval, correctly handling millisecond-rounded 60/59.94 and 30/29.97 fps timestamps.
- Prefer valid stream/decoder FPS metadata over render callbacks that can omit frames. Detect MPEG-2 interlaced sequence flags and raw H.264 SPS alongside Annex B headers; retain progressive 30 fps.
- Show codec, source, decoder and timestamp FPS in channel details so a remaining provider-specific discrepancy can be reported without credentials or URLs.
- Keep release checks deterministic across voice-service restarts and avoid decoding large fixture caches on the UI thread.

## 0.8.1 — 2026-10-25

- Make guide and Library search ignore spacing, punctuation and provider text styling, so KING 5 also finds KING5.
- Reduce the README to key features, remote controls and installation.
- Reuse cached quality readings directly and avoid repeated metadata parsing for large unlabelled lineups.

## 0.8.0 — 2026-10-25

- Return directly to live TV with Back from on-demand playback; reopen Library at the cached catalog and pause the movie while browsing.
- Replace large Library category/search diffs on the UI thread with linear filtering and a visible-row refresh; defer focus until pending adapter changes settle.
- Restore the previous Media3 HTTP transport for live streams while retaining consent-aware VOD redirects. Avoid whole-playlist refiltering for ordinary FPS updates, save observations off the UI thread, and suppress tiny refresh-rate fluctuations.
- Pause queued artwork downloads when Library/Sports is hidden.
- Import provider-reported stream dimensions/FPS and explicit quality labels for immediate filtering. Prefer observed playback, hide unknown quality, and add a direct FHD+ / 60 fps preset.
- Accept the feed’s minute-precision timestamps and replace live-only Sports with NFL current-week and MLB daily scoreboards, including team logos, scores, final/live/scheduled status, and local start times. Match playlist channels only when a game is selected; label broadcaster-only matches as possible coverage.

## 0.7.0 — 2026-10-25

- Follow bounded HTTP redirects explicitly for provider media, preserving byte ranges/resume and checking source HTTP consent before every hop. Report blocked HTTP downgrades clearly and strip sensitive headers when origins change.
- Give guide/category lists a single scrolling owner and center the focused row as navigation advances. Make Library reachable from Sports, and define explicit navigation between Sports controls, team selectors and results.
- Build Sports from external MLB/NFL schedules instead of waiting for a provider EPG scan. All live shows confirmed games even without matched channels; each followed team shows its live or next game. Cache schedules, show incremental results, and distinguish EPG-confirmed channels from possible broadcaster matches.
- Show cached/in-memory Library catalogs immediately, fetch titles before optional categories, and move catalog writes off the first-render path. Add poster grids, movie detail pages, provider artwork/descriptions and episode thumbnails/details.
- Add optional encrypted AIO Metadata configuration for movie/series and identifiable live-programme enrichment. Use provider IDs or conservative exact title/year matching, preserve provider playback, and keep optional enrichment off the playback path.
- Prefer measured frame cadence over misleading declared FPS. Recognize H.264 interlaced sequence information, label field cadence explicitly, and use the corrected cadence for filters and refresh-rate requests while preserving progressive 30 fps.

## 0.6.0 — 2026-10-25

- Verify current MLB/NFL provider matchups against ESPN live event state. Hide future, delayed, suspended, finished, stale, and unverified games; retain progress and manual retry without sending playlist data to the sports service.
- Add a built-in font picker in Settings and Theme: Default, Compact, Casual, Serif, and Monospace. Apply fonts across native UI and canvas guide rows without restarting playback.
- Add a separate, virtualized Library for provider movies, series, seasons/episodes, categories, text search, and Continue watching. Load Xtream catalogs on demand with encrypted caches; recognize explicitly declared M3U media entries.
- Add advertised Xtream and M3U catch-up archives, available from the Library and channel details, with bounded archive history and completed-programme selection.
- Reuse the same player for on-demand playback with pause, seek, start over, audio/subtitles, next episode, encrypted per-playlist resume positions, and explicit Library/Live TV return. Keep live channel history and recovery rules separate.

## 0.5.0 — 2026-10-25

- Replace the Settings menu with a full-screen translucent two-pane surface: scroll settings on the left, change values on the right, and keep the existing player running behind it.
- Show live Sports only, with All live and followed-team selectors. Reuse current EPG, reduce Sports requests to four listings per channel across at most 64 prioritized networks, and show animated progress while results arrive incrementally. Prevent continuous responses from postponing the render.
- Keep rapid category navigation anchored to one pending focus target; stop held Up at the first channel instead of cascading into top controls. Disable scroll-edge effects and redundant boundary focus changes.
- Update Android TV launcher and application icon/banner declarations. The pixel TV is the S, followed by PARE, forming one SPARE wordmark on the home-screen tile.

## 0.4.1 — 2026-10-25

- Make future guide browsing independent for each channel, with Left to rewind, Back to return to live, and reachable Now controls. Add a persistent 12/24-hour time preference.
- Clear search on category/Favorites selection, redraw favorite stars and EPG in place without stealing focus, with dark favorite-star focus contrast, and confirm additions/removals. Preserve sidebar focus with targeted updates and smooth offscreen scrolling; stop repeated Left on Search from bouncing focus.
- Separate Guide/Sports navigation from provider categories. Broaden league-aware matchup matching, request deeper bounded sports EPG, diversify network warmup, distinguish filtered/missing listings, and offer labeled team-channel fallbacks. Refresh XMLTV without retuning.
- Keep Xtream short EPG keyed by stream instead of shared XMLTV IDs; migrate only unambiguous cached guide entries and preserve explicit XMLTV overrides.
- Replace the channel-change text block with a compact translucent programme card, subtle edges and shadows. Add an original minimal pixel-art TV icon and matching launcher banner.

## 0.4.0 — 2026-10-25

- Add a Sports section beside Favorites: follow MLB/NFL teams, discover live/upcoming matchups in the provider guide, and choose among matching channels while keeping the live preview running.
- Group alternate broadcasts, preserve separate doubleheaders, prioritize favorite channels, and exclude ambiguous team names, replay/highlight listings, expired games, and games more than 48 hours away.
- Reuse cached EPG and warm at most 160 likely sports/favorite channels with the existing two guide workers. Pause Sports guide requests on account/rate-limit errors or repeated failures. Respect category, observed-quality, and unavailable-channel filters; never probe video streams.
- Keep a channel’s verified FPS when a same-size HLS/caption restart temporarily supplies no frame-rate metadata; replace it when a new valid reading arrives, and remeasure after resolution changes.
- Move focus to already-visible channel/category/game rows synchronously so rapid direction-then-OK input cannot activate the previous row; retain bounded layout waits for off-screen rows.
- Keep team preferences on-device, support all-games browsing, and retain direct sidebar/toolbar navigation and per-channel captions from 0.3.1.

## 0.3.1 — 2026-10-24

- Jump left from any category to Search/Settings, return down to the same category and scroll position, and keep vertical category navigation inside the list. Menu opens Settings from guide or playback.
- Expose CC1/CEA-608 and Digital 1/CEA-708 compatibility readers when transport-stream caption descriptors are absent, including HLS masters that omit captions or incorrectly declare NONE. Preserve actual provider descriptors and separate subtitle tracks.
- Make Automatic select supported subtitles even without matching language/default metadata, and try the two unlabeled compatibility services until captions appear, with a bounded search that never retunes the video.
- Save per-playlist/channel subtitle choices, keep an app-wide default, and expose Subtitles in channel details. Validate actual embedded caption rendering, per-channel persistence, and deep-sidebar navigation.

## 0.3.0 — 2026-10-24

- Keep new channel selections in the guide preview, retain explicit full-screen actions, and keep vertical remote navigation in the channel list across off-screen rows.
- Replace the external speech activity with native microphone permission and inline recognition, cancellation, timeout, and editable fallback.
- Add visible-category presets/custom selection and five guide color themes without restarting playback. Explain temporarily hidden channels and subtitle availability.
- Persist automatic captions across channels, inspect HLS segments for undeclared captions when enabled, and keep explicit track selection.
- Install verified updates through native PackageInstaller sessions, requesting confirmation-free self-updates on Android 12+ with system-confirmation fallback.
- Replace channel-opening/buffering text with a thin progress bar, reduce initial buffer threshold from 2.5 to 1.5 seconds while retaining the larger rebuffer cushion, and make media-timestamp FPS measurement tolerate duplicate/skipped frames.

## 0.2.3 — 2026-10-24

- Reduce guide controls to search/settings icons above the categories. Move Playlists, updates, and temporary filter bypass into Settings; remove My guide/Browse all buttons.
- Enlarge live preview to 320×180 dp with space for programme details; make preview the default for unset preferences.
- Show FPS in channel details, with bounded media-timestamp measurement when the stream omits frame-rate metadata.
- Resume cached playlists quietly, refresh lineups at most every six hours unless requested, and prefetch nearby/favorite EPG with two workers. Preserve merged guide snapshots through serialized encrypted writes and flush on exit.

## 0.2.2 — 2026-10-24

- Replace the search dialog with an inline guide search strip, live channel results, immediate TV voice input, and editable text fallback. Canceling voice input keeps the search usable.
- Show a compact programme/channel details panel on OK while watching, with guide, previous-channel, and favorite actions. Keep Back-to-guide and hold-Back behavior.
- Add single-press Left to toggle between the last two successfully played channels. Preserve source-local history across restarts and keep failed tune attempts out of it.
- Preserve Xtream category-array order in the encrypted guide cache and M3U group first-appearance order. Default to provider order; offer stable USA-first ordering in Settings.
- Extend remote, search/voice-result, details, history, category-order, and cache regression coverage.

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
