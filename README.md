# Spare

A minimal, free, open-source IPTV player for Android TV.

Spare plays your own live-TV sources and provider-supplied movies, series, and catch-up archives. It includes no channels, subscriptions, advertisements, analytics, or app account. Current prerelease: **0.6.0**. License: MIT.

## What it does

- M3U playlist URLs or Xtream Codes server/username/password.
- Several saved sources, one active at a time.
- Up to 100,000 channel entries per source, within bounded download sizes. Larger lists produce an explicit limit message.
- A compact guide with channel logos, clear remote focus, and a choice of translucent video overlay or small live preview.
- Provider categories in a dedicated left sidebar, inline channel-name search with TV voice input, and Favorites. Category order follows the Xtream category array or M3U first appearance; Settings offers USA-first grouping without alphabetizing either region. Provider channel order is preserved.
- Sports: follow MLB/NFL teams and match the provider’s current EPG against external live event status from ESPN. Scheduled, delayed, suspended, finished, and unverified games are excluded. Matching broadcasts are grouped into channel choices; Favorites appear first. Games open in the existing preview.
- Xtream programme data cached and prefetched for the current channel, visible/nearby rows, and up to 24 favorites, with at most two concurrent requests. M3U XMLTV and explicit XMLTV overrides remain supported. Guide data stays in private encrypted cache.
- Per-source guide filters: selected categories, minimum HD/FHD/4K resolution, and FPS exclusions. Uncheck 25/50 fps while keeping 24/30/60, for example; 23.976, 29.97, and 59.94 belong to their nominal families. Unknown FPS stays hidden while FPS filtering is active. Streams without FPS metadata are measured from stable media timestamps, not the TV output rate.
- Quality comes from observed playback, never channel names. Unknown quality is hidden when quality filters are active. Settings → Guide filters → Show unverified channels temporarily bypasses the profile so channels can be played and verified; there is no background scanning.
- Terminal 404/410 channels are hidden for 15 minutes after startup endpoints confirm they are missing, without bulk probing. Settings can retry or restore them. Network, account, and missing-segment errors do not prove a channel is absent.
- MPEG-TS access-unit detection, Xtream TS/HLS fallback, a manual stream-format setting, and a visible startup timeout with safe diagnostics. Channel opening/buffering uses a thin progress bar without status text; initial playback requests a 1.5-second buffer. Buffering has a delayed indicator and an eight-second resume target; adaptive live playback requests a 12-second target offset when the provider window allows it. Recovery is bounded to two reconnects in two minutes. These improve tolerance of short disruptions but cannot fix a provider outage or inadequate throughput. Playlist and guide downloads show their current stage and byte progress when known.
- Last-channel resume, one-press recall between two watched channels, programme/channel details, audio/subtitle selection, and automatic frame-rate matching where supported.
- A separate Library for Xtream movies and series, category browsing, inline text search, season/episode selection, continue-watching, and next-episode controls. M3U entries explicitly marked as media also appear as movies.
- Provider catch-up archives: Library → Catch-up lists supported channels; the channel-details Catch-up action opens that channel directly. Xtream archive metadata/history and declared M3U `xc`, `default`, `append`, and `shift` catch-up formats are supported. Only completed programmes inside the advertised archive window are offered. Unsupported template placeholders are rejected.
- On-demand pause, seeking when the stream permits it, start over, audio/subtitle selection, and return to Library or Live TV. Movie/episode positions are saved locally; finished titles leave Continue watching. Live channel history, quality filters, and missing-channel rules stay separate from on-demand playback.
- Quiet update badge, signed APK updates initiated in the app, and Android installation confirmation when required.

Target hardware for first physical validation: **2026 onn 4K Pro with a 75-inch Samsung 4K TV**. Android TV 8.0/API 26 or newer is required. Full refresh-rate requests use Android 12/API 31 or newer; older systems use available display-mode selection. TV/HDMI modes and Android preferences can prevent a match. Switching may briefly blank the picture.

Settings → Font (also reachable from Theme) offers Default, Compact, Casual, Serif, and Monospace using built-in device fonts. Changes apply immediately and persist without recreating the player. Settings → Time format switches the guide, details, and Sports between 12-hour and 24-hour time. Long-press OK confirms favorite changes with a visible message and high-contrast star.

Cached playlists resume before network work, with a six-hour lineup refresh interval and an explicit refresh in Settings. Cached EPG is reused and nearby rows are warmed in the background with two workers; a 20,000-channel provider is never bulk-probed. First-time imports retain a progress indicator.

## Remote

| Where | Control | Result |
| --- | --- | --- |
| Full-screen player | Up / Down | Previous / next channel in the current guide filter |
| Full-screen player | OK | Show programme/channel details; the Guide action opens the guide |
| Full-screen player | Left | Toggle between the last two successfully played channels |
| Details | Back | Close details and keep watching |
| Guide | Up / Down | Browse channels without changing the stream |
| Guide | OK | Tune a different channel in the preview; select the playing channel to expand |
| Guide | Long-press OK | Toggle favorite |
| Categories | Left / Down from controls | Jump to Search/Settings / return to the same category without scrolling |
| Anywhere | Menu (if present) | Open Settings |
| Guide | Left / Right | Rewind / advance this channel by 30 minutes; Left at live opens categories. Back resets a future row to live; Up from the first row reaches Now. |
| Guide | Back / hold Back | Move between channel/sidebar focus / expand the current channel |
| Full-screen live player | Back | Open guide |
| Library | Movies / Series / Continue / Catch-up | Switch provider content type |
| Library | Left / Right | Move between categories and titles |
| Series / channel archive | Back | Return to series / archive channels |
| On-demand player | OK | Show playback controls and seek bar |
| On-demand player | Back | Hide controls, then return to Library |
| On-demand player | Media Play/Pause / Rewind / Fast-forward | Pause or seek 30 seconds when supported |
| Search | Down / Done | Browse the live-filtered channel results |
| Search | Back | Close inline search; the query remains active until cleared |

Search and Settings icons sit above the category sidebar; the guide has a larger 320×180 dp live preview. Playlists and Updates are inside Settings. Search requests microphone permission once, then listens inline using the configured speech service. There is no speech-app chooser or external recognition activity. Voice can be retried, canceled, or replaced with keyboard input; unavailable speech input leaves the field usable. Search spans provider categories within the current guide-quality profile. Settings → Playlists adds, switches, edits, or removes playlists. Settings contains guide video style, guide filters, visible-category presets/custom choices, category order, five color themes, a built-in font picker, Xtream stream format, audio/subtitle controls, refresh, About, and an explicit Exit action. Text entry works with the TV keyboard and the paired Google TV phone remote; there is no custom phone pairing server.

Settings → Categories offers label-based English-speaking regions, USA/Canada, UK/Ireland, and manual choices; these labels cannot guarantee a stream’s spoken language. Theme changes preserve playback. Settings → Subtitles and the channel-details Subtitles button save choices for the current channel: use the app default, Off, Automatic, or a specific track. App default changes the default for channels without an override. Automatic selects supported text tracks even without language/default labels. For transport streams without caption declarations, CC1 and Digital 1 compatibility readers are available; Automatic tries them for at most six eight-second intervals and stops once captions arrive, without reconnecting video. Compatibility services are guesses, not proof that a programme carries captions. Real provider caption descriptors and separate subtitle renditions take precedence. No captions can be created when the provider supplies none.

Guide and Sports have separate fixed controls above provider categories. From All channels, press Up to reach them; Left from any category jumps to Search/Settings. Sports shows MLB/NFL matchups playing now. Teams adds followed-team selectors; All live shows all matched live games. Select a game to choose its channel, keep browsing with the preview playing, and hold Back to expand.

Sports reuses current provider EPG before network work. It recognizes team names, nicknames, league-qualified abbreviations, and unambiguous cities; generic listings cannot identify a matchup. Provider timing alone does not prove a game has started. Only current provider matchups also reported in progress by the public ESPN scoreboard feed are shown. Scheduled, delayed/suspended, finished, and unconfirmed games are excluded. Live status refreshes at most once per minute while Sports is visible and expires after two minutes. Feed failures hide unverified games with an explanation; Refresh retries. Four small bounded requests cover today and yesterday in Eastern time for both leagues, including overnight games. The public web feed can change; it is not a supported API contract. No provider URLs, credentials, watched channels, or team preferences are sent to ESPN. Xtream checks at most 64 prioritized, diverse networks with two workers and four short-guide entries per channel. Current cached listings need no extra request. An animated progress bar and checked-channel count remain visible while results appear incrementally. Requests pause on account/rate-limit errors or repeated failures. Quality/category/unavailable-channel filters apply to results; category preferences also narrow discovery. Refresh retries current guide entries or XMLTV without restarting video. Followed teams and the selected team stay on-device.

Settings is a full-screen translucent panel over the same player: navigate settings on the left and change values on the right. Left returns to settings navigation; Back from navigation closes it. Category choices are virtualized for large playlists. Android TV launcher resources use one pixel SPARE wordmark: the TV glyph forms S, followed by PARE.

Provider archives and on-demand content require the provider to supply them; Spare does not record streams or create missing archives. Recording, multiview, and local live-TV timeshifting remain out of scope. Library catalogs load on demand, use an encrypted six-hour cache, and never delay live startup. Series episode lists load only when selected.

## Install and update

Use APK attachments from [GitHub Releases](https://github.com/mot1us/spare/releases). Development APKs use a separate `.debug` app ID and do not self-update into production.

Trusted main-branch pushes build, test, sign, and publish automatically. The initial tracked release channel is `prerelease` pending the onn/Samsung checklist; the stable channel is enabled in a subsequent versioned update after that checkpoint.

After the first stable release, this permanent link will install the latest stable APK:

`https://github.com/mot1us/spare/releases/latest/download/spare.apk`

A Downloader code can point to that URL. Register it once through [AFTVnews](https://go.aftvnews.com/) after a stable release exists. A code is a shortcut; it does not host the APK. CAPTCHA registration requires a human.

The app checks for updates at most daily, without interrupting playback. Stable builds use the latest stable `update.json`; prerelease builds use GitHub's public Releases API to find the newest published testing or stable manifest. Drafts and foreign manifest URLs are rejected. It downloads only when asked, checks SHA-256, package ID, increasing version code, and the installed signing identity, then stages a native PackageInstaller self-update. Android 12+ is asked to install without another confirmation, with the Android confirmation prompt retained when required. Android closes Spare to finish installation; reopen it from your apps. The update that first installs 0.3.0 still uses the older updater; this path takes effect for subsequent updates. Android may require enabling “install unknown apps” separately for Spare, even if it is already enabled for Downloader. Prereleases remain excluded from stable update discovery. Version 0.1.0 needs one manual update to 0.1.1 to fix testing-channel discovery.

## Build and verify

Use JDK 17 or 21 and Android SDK 36. The checked-in Gradle wrapper downloads the pinned build tool and checks its SHA-256. Configure `ANDROID_HOME` or an ignored `local.properties` file with `sdk.dir`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Production dependencies are locked in `app/gradle.lockfile`. Deliberate dependency updates must refresh that file with `--write-locks` and pass checks. The unsigned optimized APK is built unless release-signing environment variables are configured. See [releasing](docs/RELEASING.md) and [device testing](docs/TESTING.md).

## Privacy and security

Sources, credentials, guide/library caches, and on-demand watch history (up to 100 titles per playlist) are encrypted with an app-specific Android Keystore key in private storage; cloud backup/device transfer is disabled. Favorites, current/previous watched-channel IDs, quality observations (up to 5,000 channels), temporary unavailability, guide video style, followed MLB/NFL teams, per-channel subtitle choices, and guide preferences stay in private preferences. Removing a source removes its cached guide, Library catalogs, watch history, and preferences. Uninstalling clears local app data.

Voice search requests RECORD_AUDIO only when Search/Voice is selected. The configured Android recognition service captures/processes speech; its privacy policy applies and it may use its own network service. Spare stores no audio, restores playback volume when listening ends, and stops listening when search closes or the app leaves the foreground. Permission denial or recognition failure leaves text search available. Voice availability and the dedicated remote Assistant button are controlled by the TV's software; Spare's Search action requests in-app channel transcription rather than a web search.

Provider traffic, guide downloads, and logos contact the URLs supplied by your provider. Sports live-status checks contact ESPN’s public scoreboard feed only while Sports is visible. They contain league/date queries and no playlist or credential data. Update checks and APK downloads contact GitHub (including api.github.com for prerelease discovery). There is no Spare credential service or telemetry endpoint. Provider data never becomes HTML or executable instructions.

HTTPS is the default. HTTP requires explicit consent for each source and exposes data in transit. Every media manifest, segment, key, subtitle, image, and guide request is restricted to HTTP/HTTPS and the source’s HTTP policy; file/content/executable schemes are rejected. HTTP redirect downgrades are rejected for HTTPS-only sources. Imports, decompressed data, XML nesting, logos, and field lengths have size limits; DTDs and custom XML entities are rejected. Import work runs off the UI thread. App and Media3 release logging omit provider credentials and URLs.

Report security issues using the process in [SECURITY.md](SECURITY.md). See [third-party notices](THIRD_PARTY_NOTICES.md).
