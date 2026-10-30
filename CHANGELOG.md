# Changelog

## 0.21.1 — 2026-10-29

- Give movies and episodes a five-second startup cushion and twelve-second rebuffer cushion while preserving live TV buffering and the existing read-ahead limits. Stop background catalog imports during on-demand playback.
- Monitor on-demand playback for stuck buffering or frozen video, reconnect from the saved episode/movie position with bounded retries, and preserve that position when leaving the app. Allow extra startup time for on-demand files; deliberate pauses and Library browsing never trigger recovery.

## 0.21.0 — 2026-10-29

- Play a fresh confirmed Sports card match immediately instead of discarding it for a second search. Keep a new search for expired, changed or unconfirmed coverage, and preserve playback fallback choices.
- Recheck in-flight coverage when relevant guide data changes or a new matching programme arrives; unrelated guide updates no longer keep an unresolved search restarting.
- Keep the active live or on-demand stream when leaving the Library browser. Back or Stop during a movie/episode stops it and returns to Library without automatically tuning an older live channel; series return to their episode list.
- Add encrypted Library favorites for movies and series, poster/header favorite controls, watched episode history, new/unwatched episode badges and Mark all watched. Check saved series in bounded metadata-only background requests when Favorites opens.

## 0.20.3 — 2026-10-29

- Restore and build one compact encrypted VOD title index in the background, shared by Library, search and live programme matching. Browse/search cached titles without decrypting individual catalog pages, and show Watch VOD from beginning only for known catalog matches. Suppress files that returned 404 for one hour.
- Refresh legacy catch-up capabilities from per-channel provider flags. A retention duration alone cannot enable catch-up; suppress stale capability badges until current metadata is available.
- Check higher-resolution event feeds even after finding a lower-resolution game, include generic UHD channels in bounded coverage checks, and invalidate cached results when another channel gains matching EPG data. Keep pending higher-resolution lookups when a lower-quality reply arrives.
- Poll due reminders independently of Sports page loading, with a 30-second scoreboard cache/check interval. Reminder playback uses the same refreshed coverage search.

## 0.20.2 — 2026-10-29

- Rebuild Watch from beginning as a dedicated, calm TV overlay. Start a clear movie or episode match directly; show at most four matching release choices, with no mixed live-channel or unrelated-title list. Search only movies or only series, and check remembered titles before catalog pages.
- Start a clear VOD movie or numbered episode match as soon as it arrives, without waiting for unrelated catalog downloads. Keep cached searches busy until their reader finishes, and let a valid match play even if another catalog fails.
- Match DV, Dolby Vision, HDR and codec labels as quality variants; tolerate missing provider years while keeping conflicting known releases separate. Selecting a movie from the live-to-VOD results now starts it from the beginning directly.
- Save encrypted partial catalog indexes during long imports so interrupted searches can reuse titles after an app restart instead of starting empty.

## 0.20.1 — 2026-10-28

- Keep playlist search focus inside its results and stop delayed guide navigation from taking over the remote. Use cached live-channel search keys instead of repeatedly normalizing the full lineup on the UI thread.
- Batch encrypted VOD catalog writes, index title keys to skip unrelated pages, reuse the full catalog when browsing categories, and keep metadata imports through search query changes and dismissal. Publish usable results while imports continue.
- Restore primary-player audio and audio-track selection when starting a movie or episode, handle audio focus, and honor provider file extensions and permitted direct playback URLs without probing alternate movie formats.

## 0.20.0 — 2026-10-28

- Add Watch VOD from beginning to live channel details. Play unique movie matches and explicitly identified episodes from zero; offer choices for remakes, ambiguous titles or episodes the guide does not identify. Channel catch-up stays separate.
- Mark provider-advertised catch-up channels in the guide and count the full playlist inventory in Library. Search archives beyond the broadcaster shortlist, support keyed EPG responses and the legacy Xtream archive endpoint, and share browsed archive history with Sports and the saved guide.
- Use one native playlist search from Guide and Library, with live channels, movies, series and known episode results. Show live matches immediately; load encrypted movie/series catalogs on explicit search and reuse them for six hours. No video probes or bulk episode requests.
- Combine up to eight cities/states in Local, with autocomplete for additional regions and duplicate stations removed.
- Remove the redundant Nostalgia shortcut from the theme editor; retain Nostalgia in the style picker.

## 0.19.5 — 2026-10-28

- Process Sports guide replies as they arrive and stop pending metadata requests once a game match is confirmed. Preserve completed replies at the timeout boundary while keeping the existing two-request concurrency and eight-channel budget.
- Keep completed searches through unrelated guide refreshes, preserve historical programmes when merging lookup data, and prevent those merges from invalidating their own cached match.
- Bound saved replay data to the reader's actual UTF-8 byte limit, prioritize recent games, and reuse channel identity hashes during save/restore.

## 0.19.4 — 2026-10-28

- Refresh local game coverage when Watch Best or a reminder starts playback, rather than reusing a previously inferred broadcaster list. Require current game evidence before automatic broadcaster tuning and exclude known conflicting programmes.
- Include a bounded, varied selection of league/event channels when current guide data needs confirmation. Preserve 720p, lower FPS and unknown-quality game matches; rank resolution and FPS only after matching the game.

## 0.19.3 — 2026-10-28

- Preserve historical programmes through each channel's catch-up window when live guide data refreshes, keeping current and upcoming listings available within bounded storage.
- Keep confirmed Sports replay matches until their provider archive expires instead of dropping them after ten minutes. Save bounded replay references in encrypted playlist storage and restore them across app restarts, validating current channel identity and retention.

## 0.19.2 — 2026-10-28

- Keep discovered Sports card channels visible when returning to Sports and while refreshing expired match data. Show Finding stream only for cards without a valid saved match; preserve labels when cancelling a quiet refresh.
- Retain playlist, relevant-guide and game/programme-boundary invalidation. Automatic playback still refreshes expired evidence; background card discovery opens no video connections.

## 0.19.1 — 2026-10-28

- Give transient provider reads Media3's normal bounded retries instead of escalating after one retry, and allow longer quiet intervals on live HTTP connections before declaring a timeout. Keep account/rate-limit errors and stalled-playback recovery bounded.
- Let HLS honor provider segment timing and hold-back instead of forcing a 12-second live offset. Preserve normal playback speed and existing startup/rebuffer thresholds.
- Move decoder metadata reads and quality/display updates out of the per-frame hot path. Add a local channel-details line for transport, buffered seconds, stalls, load errors and reconnects without exposing provider URLs.
- Publish Android updates independently of optional Mac packaging so an unavailable Mac runner cannot block a checked, signed APK.

## 0.19.0 — 2026-10-28

- Add a native macOS preview for Apple Silicon and Intel: M3U/Xtream playlist setup, live playback, cached guide, categories, favorites, search, audio/subtitle tracks and previous-channel controls.
- Add desktop Sports schedules, live/final scores, followed teams, league preferences, score hiding and playlist stream matching ordered by coverage and resolution. Unknown FPS remains playable; no background stream probes.
- Save Mac credentials in Keychain and encrypt lineup/guide caches. Package the VLC runtime, source/license notices and Mac download alongside the Android APK in GitHub Releases.
- Preserve Android TV playback and feature behavior. The Mac preview uses manual release downloads and is ad-hoc signed pending Developer ID setup.

## 0.18.2 — 2026-10-28

- Discover catch-up across completed Sports cards beyond the first eight, progressively check more archive variants, reuse exact-channel histories and retry negative results after guide updates. Keep replay badges for the same lifetime as their discovery cache and separate histories for channels sharing XMLTV IDs.
- Exclude 24/7 collections and ambiguous station-name matches from Local results. Warm Local matches as the playlist loads, save an encrypted cache tied to location and lineup content, and show an animated indicator while matching.
- Recover expired live HLS segments through the existing bounded reconnect path instead of treating a missing child segment as a missing channel. Preserve buffer sizes, retry limits and account-error handling.

## 0.18.1 — 2026-10-28

- Rank Sports live, RedZone and catch-up streams by resolution first; use known/preferred FPS only between streams at the same resolution. Preserve game coverage, archive availability and lower-quality fallbacks.
- Let Watch Best play the highest-resolution match immediately when FPS is unknown. Accept working playback without an FPS trial or quality-driven retune; learn and cache FPS through the existing player, with no background stream probes.
- Clarify Sports quality settings and metadata counts.

## 0.18.0 — 2026-10-28

- Add a live theme editor with a persistent guide preview, compact Look/Colors/Type/Guide tabs, custom colors, fonts, corner/border controls, density, spacing, opacity and video preview options. Save changes automatically without restarting playback.
- Group frame matching, tracks, subtitles, stream format and recovery controls under Playback. Replace duplicate track dialogs with the native settings surface; streamline Guide and Library navigation.
- Add inline automatic Local city/state suggestions and matching, more guide header/row padding, a trophy Sports icon and automatic Sports refresh.
- Treat Sports quality consistently as preferences across live results and RedZone, preserve all playable variants, label provider versus observed evidence accurately, and stop guessing provider frame cadence or video aspect ratio.
- Discover catch-up from saved guide data and bounded background provider metadata across archive-capable variants, independently of the live pick. Cache archive checks and show checking/confirmed/not-found states without hiding fixtures.
- Fix recovery timing after buffering and restore first-frame timeouts after pause/resume; preserve the existing buffer and bounded reconnect policy. Reduce repeated font/poster binding work and remove unused duplicate controls.

## 0.17.2 — 2026-10-27

- Keep live-stall monitoring, reconnect and startup callbacks active when opening other game streams, including when returning to the current channel without selecting an alternative.

## 0.17.1 — 2026-10-27

- Add Other streams for this game to the OK details panel, focused first for recognized games and available during buffering. Reuse discovered matches, preserve game context after manual selection or reconnect, and open selected alternatives full screen.
- Use persistent green Live pills and blue Catch-up badges on confirmed replay cards, show the replay channel/quality, and distinguish unconfirmed archive support. Replay lookup includes lower-quality archive variants independently of the live pick.
- Offer game alternatives from the playback error panel for manually selected Sports streams too. Recognize current-guide matchups from EPG or channel titles and find their streams on demand.

## 0.17.0 — 2026-10-27

- Recover sustained live playback stalls with per-attempt progress monitoring, restore recovery after pause/resume and first frames, and keep reconnects bounded. Add Reconnect to channel details while preserving short-buffer recovery and existing buffer sizes.
- Add a Local guide tab and Settings → Local channels for a U.S. city/state. Match your playlist offline using station call signs and explicit provider location labels; include a small attributed historical FCC reference, with no GPS or location requests.
- Reuse Sports card summaries during focus changes and compute new summaries off the UI thread. Show Play replay directly on final-game cards for confirmed, unexpired catch-up matches; other eligible cards go straight to finding replays.
- Treat Sports quality as a preference: prefer FHD/60, then fall back to lower-resolution, lower-FPS or unverified matches. Play a single fallback immediately or offer a chooser; keep working playback instead of rejecting its measured quality. Selecting a guide channel opens full screen.
- Add guide background opacity percentages under Guide video, reserve space while the hidden dock is open or closing, and move the TV graphic after spare on the Android TV launcher banner and icon.

## 0.16.2 — 2026-10-27

- Let background Sports matching finish its local playlist pass instead of discarding large lineups after 1.5 seconds. Show checked-channel progress and preserve cancellation when tuning or leaving Sports.
- Keep completed matches across unrelated EPG updates and retry when relevant coverage changes during a search. Show found streams separately from verified automatic-play quality so missing quality data does not hide discovery.

## 0.16.1 — 2026-10-27

- Move resolution and FPS preferences into Sports settings → Stream quality, preserving saved per-playlist choices. Keep all qualities visible in the main guide, Favorites and search.
- Keep category visibility separate, remove the obsolete guide quality pause controls, and change Sports quality without retuning playback or resetting the guide selection.
- Fix Sports cards stuck on Finding stream: keep completed matches when quality changes, show loading only during active work, and defer unfinished searches to selection. Prepare cards from saved data without provider requests and reuse those results when confirming coverage.
- Cancel background matching before channel changes and bound stalled live reads to 10 seconds instead of 30, preserving the playback buffer and retry policy.

## 0.16.0 — 2026-10-27

- Prepare live-game channel matches in the background and cache them for the active app session. Prioritize focused cards, pause work during buffering or multiview, and cancel it when leaving Sports.
- Show matched channel, resolution and FPS evidence on Sports cards without moving focus or rebuilding the grid.
- Prefer known FHD+/60 streams; automatically try FHD/4K coverage with unknown FPS and measure it during playback. Keep strict guide filters and exclude known lower rates from automatic selection.
- Try up to three game streams after failed starts or measured lower quality, then show alternatives. Preserve account/authentication errors and offer Other game streams for later failures.
- Prioritize provider quality before bounding broadcaster variants so a known 60 FPS channel is less likely to be omitted.

## 0.15.1 — 2026-10-27

- Add an optional Sports setting to find and play the best known FHD+/60 stream when selecting a live game card, including calendar games. Show stream results if no suitable match is found.
- Keep reminder and replay actions for upcoming and completed games, and preserve manual stream selection when adding a multiview pane. Reset Sports restores automatic playback to off.

## 0.15.0 — 2026-10-27

- Show games beside the Sports calendar, including local start times, live/final states, saved-score preferences and counts on loaded dates. Open game actions directly from the calendar.
- Load highlighted dates after navigation settles, reuse cached schedules, reject stale responses and keep playback running under the overlay.
- Let Watch Best try a known FHD+/60 advertised-broadcaster stream when no exact game listing exists, clearly label unconfirmed coverage and reject guide entries for other games or replays.
- Open selected Sports stream results full screen; Back returns to the results. Show fallback explanations prominently and use Find streams for stale live status.

## 0.14.3 — 2026-10-27

- Let Watch Best use the same explicit provider or playback FHD+/60 evidence as guide filters, including 4K/59.94; keep measured quality authoritative.
- Explain missing FPS separately from unconfirmed game coverage in the stream results.
- Decode Sports card logos at higher resolution with smooth scaling.
- Replace the date dialog with a full-screen translucent month calendar, remote navigation, month controls and Today.

## 0.14.2 — 2026-10-27

- Keep Sports focus on the latest remote action, prioritize focused-game artwork and cancel obsolete queued artwork work.
- Retry artwork skipped while paused or busy, and clear completed Sports placeholders even when the page is hidden.
- Preserve Library detail focus, reject stale backdrops, return M3U movies to their catalog and make episode Refresh fetch fresh data.
- Cache numeric row IDs, remove duplicate channel scans and batch quality saves to reduce scrolling and playback overhead.
- Refresh guide time labels with programme rows; clear removed-playlist playback/resume state and stale background refresh results.

## 0.14.1 — 2026-10-26

- Open Sports on Today, add Live across enabled leagues and keep Calendar for other dates. Live includes unfollowed games and excludes replay/upcoming cards and unconfirmed RedZone status.
- Hold the first Sports layout behind fixed card placeholders, reveal the completed scoreboard together and keep existing cards stable during refresh.
- Add a confirmed reset of Sports teams, leagues, reminders and preferences.
- Preserve provider resolution during incomplete playback readings, accept more explicit provider FPS metadata, recover cached label declarations and show known/unknown quality counts.

## 0.14.0 — 2026-10-26

- Rebuild Sports as a card grid with a focus-driven hero and fixed loading layout. Remove the Sports sidebar; configure leagues, followed teams, reminders and spoiler preferences in full-screen Sports settings.
- Open live, replay and reminder actions only after selecting a game. Keep replay results free of duplicate search buttons and add an NFL RedZone playlist destination without inventing live status.
- Replace both Android TV launcher banner and icon with a bespoke pixel wordmark and vintage TV, with an opaque background beyond all edges.

## 0.13.1 — 2026-10-26

- Keep the Sports dock closed during incidental focus restoration; recover the previous Sports control and require deliberate Left navigation to enter the dock. Up at the first league stays in Sports.
- Move completed-score visibility entirely to Settings → Sports. Hide final scores by default when no explicit preference exists, and remove in-card Hide/Reveal buttons.

## 0.13.0 — 2026-10-26

- Find completed Sports games in provider catch-up, using historical EPG and per-channel replay windows. Show replay expiry, bound provider lookups, and return Back to the selected Sports game.
- Add persistent completed-score hiding, per-game reveal/hide controls, and spoiler-safe Sports cards, hero details and artwork. Live scores remain visible.

## 0.12.3 — 2026-10-26

- Let Right enter Sports game cards from either left menu when the featured game has no enabled actions. Let Up return to leagues instead of trapping focus on completed games.
- Keep focus on visible stream controls when a game search has no matches.

## 0.12.2 — 2026-10-26

- Bound decoded artwork dimensions and deliver images directly to their card bindings. Avoid whole-rail image callbacks and repeated decoding after cache eviction that can overwhelm Sports navigation.
- Pause playback and Sports image work immediately when leaving the app; stop the player before background cleanup. Resume playback when returning.
- Exclude the dock from automatic Sports focus search without blocking deliberate remote navigation into it.

## 0.12.1 — 2026-10-26

- Stabilize fractional FPS readings without losing real channel/frame-rate changes.
- Speed up game coverage lookup with target-team prefiltering, independent search workers, bounded EPG confirmation and visible search/tuning progress. Include live event-channel title matches without EPG.
- Add selected leagues and full-screen team management using the Settings layout.
- Enter hero actions directly from league navigation; retain horizontal card focus while scrolling and prevent accidental dock focus.

## 0.12.0 — 2026-10-26

- Add saved game reminders, in-player live-game alerts, and one-click Watch best for confirmed FHD/60 fps coverage. Keep unmatched or unverified streams available for review.
- Measure video sample timestamps before decoding for AVC/HEVC and other video codecs, with B-frame reordering support. Feed this source rate to quality filters instead of allowing decoder/display cadence to undercount it.
- Show input and display sample rates separately in channel details. Remeasure older playback FPS caches while retaining provider metadata and last-channel resume.

## 0.11.0 — 2026-10-26

- Synchronize encrypted file access across refresh jobs and app instances to keep source saves atomic.
- Open saved channels before loading EPG; keep encrypted lineup and guide caches independently. Refresh without restarting streams or resetting guide navigation.
- Add per-playlist launch and interval schedules for channels and EPG, quiet progress, last-success status and retry controls. Use native Android background jobs; keep usable data on failures.
- Share a bounded disk cache across Sports hero images and thumbnails, reuse pending artwork requests, retry transient failures and prefetch nearby matchups.
- Add dated game cards, Tomorrow/date browsing and NFL hero priority for live or tonight’s games. Preserve card focus across score/art updates and return Sports home to Today.
- Label confirmed versus possible coverage and provider versus playback quality; preserve Multiview’s retained surfaces and selected audio.

## 0.10.2 — 2026-10-26

- Replace Android TV launcher art with a clean Spare wordmark. Add an optional Nostalgia preset with phosphor colors, square borders, a full guide grid and monospace type.
- Open Sports on Today across supported leagues, independent of followed teams. Request explicit daily schedules and add illustrated game cards while retaining All games and My teams.
- Keep Multiview video surfaces attached and reuse pane controls when returning from browsing; avoid an intermediate full-screen resize. Label recent-channel additions as + View.

## 0.10.1 — 2026-10-26

- Add persistent frame-rate matching On/Off controls without restarting playback.
- Open Multiview directly and add recent-channel logo tiles with separate watch/add actions in playback details.
- Restore remote access to Library tabs after the navigation dock opens; keep asynchronous artwork updates from moving focus.
- Fill missing movie/series posters from optional AIO Metadata, cache artwork locally, and show series art beside episode details. Improve title matching and metadata retry controls.
- Give Sports larger layered team artwork, reject nested cast portraits as programme art, and remove unused scoreboard work.

## 0.10.0 — 2026-10-26

- Rebuild Sports around league navigation, featured game artwork, live/upcoming/final status and scores, and a remote-friendly game rail. Add NBA, NHL, Premier League, MLS and Champions League.
- Prioritize followed teams live, then their next game. Keep scoreboards independent of playlist matching and fetch artwork only for the focused game.
- Add explicit Watch together actions, two-up/four-up layouts, focus-selected audio, and game/channel picking. Release additional streams when closed or backgrounded.
- Show live-programme hero artwork from XMLTV/Xtream metadata, or optional AIO Metadata matches, with a channel-logo fallback.

## 0.9.3 — 2026-10-26

- Smooth the hidden dock with a cached slide, retarget interrupted movement, and reveal its handle after closing completes.
- Add seven color palettes, Soft/Outline/Cinema styles, and six offline device font choices; refresh the dock appearance with the selected theme.

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
