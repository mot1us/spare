# Spare

A minimal, free, open-source IPTV player for Android TV.

Spare plays your own live-TV sources. It includes no channels, subscriptions, advertisements, analytics, or app account. Current prerelease: **0.2.1**. License: MIT.

## What it does

- M3U playlist URLs or Xtream Codes server/username/password.
- Several saved sources, one active at a time.
- Up to 100,000 channel entries per source, within bounded download sizes. Larger lists produce an explicit limit message.
- A compact guide with channel logos, clear remote focus, and a choice of translucent video overlay or small live preview.
- USA-first provider categories in a dedicated left sidebar, channel-name search, and Favorites; provider channel order is preserved.
- Xtream programme data fetched for the current channel and visible guide rows, with at most two concurrent requests. M3U XMLTV and explicit XMLTV overrides remain supported. Guide data stays in private encrypted cache.
- Per-source guide filters: selected categories, minimum HD/FHD/4K resolution, and FPS exclusions. Uncheck 25/50 fps while keeping 24/30/60, for example; 23.976, 29.97, and 59.94 belong to their nominal families. Unknown FPS stays hidden while FPS filtering is active.
- Quality comes from observed playback, never channel names. Unknown quality is hidden when quality filters are active. Browse all temporarily bypasses the profile so channels can be played and verified; there is no background scanning.
- Terminal 404/410 channels are hidden for 15 minutes after startup endpoints confirm they are missing, without bulk probing. Settings can retry or restore them. Network, account, and missing-segment errors do not prove a channel is absent.
- MPEG-TS access-unit detection, Xtream TS/HLS fallback, a manual stream-format setting, and a visible startup timeout with safe diagnostics. Buffering has its own delayed corner indicator and an eight-second resume target; adaptive live playback requests a 12-second target offset when the provider window allows it. Recovery is bounded to two reconnects in two minutes. These improve tolerance of short disruptions but cannot fix a provider outage or inadequate throughput. Playlist and guide downloads show their current stage and byte progress when known.
- Last-channel resume, audio/subtitle selection, and automatic frame-rate matching where supported.
- Quiet update badge, signed APK updates initiated in the app, and Android installation confirmation when required.

Target hardware for first physical validation: **2026 onn 4K Pro with a 75-inch Samsung 4K TV**. Android TV 8.0/API 26 or newer is required. Full refresh-rate requests use Android 12/API 31 or newer; older systems use available display-mode selection. TV/HDMI modes and Android preferences can prevent a match. Switching may briefly blank the picture.

## Remote

| Where | Control | Result |
| --- | --- | --- |
| Full-screen player | Up / Down | Previous / next channel in the current guide filter |
| Full-screen player | OK | Open guide |
| Guide | Up / Down | Browse channels without changing the stream |
| Guide | OK | Tune selected channel and return to full-screen playback |
| Guide | Long-press OK | Toggle favorite |
| Guide | Left / Right | Open categories / move the guide window forward by 30 minutes; timeline buttons move earlier or return to Now |
| Guide | Back / hold Back | Move between channel/sidebar focus / expand the current channel |
| Full-screen player | Back | Open guide |

General controls are above the guide. The Sources button adds, switches, edits, or removes sources. Settings contains guide video style, guide filters, Xtream stream format, audio/subtitle controls, refresh, About, and an explicit Exit action. Text entry works with the TV keyboard and the paired Google TV phone remote; there is no custom phone pairing server.

Pause/rewind, recording, multiview, movies, and series are outside version one.

## Install and update

Use APK attachments from [GitHub Releases](https://github.com/mot1us/spare/releases). Development APKs use a separate `.debug` app ID and do not self-update into production.

Trusted main-branch pushes build, test, sign, and publish automatically. The initial tracked release channel is `prerelease` pending the onn/Samsung checklist; the stable channel is enabled in a subsequent versioned update after that checkpoint.

After the first stable release, this permanent link will install the latest stable APK:

`https://github.com/mot1us/spare/releases/latest/download/spare.apk`

A Downloader code can point to that URL. Register it once through [AFTVnews](https://go.aftvnews.com/) after a stable release exists. A code is a shortcut; it does not host the APK. CAPTCHA registration requires a human.

The app checks for updates at most daily, without interrupting playback. Stable builds use the latest stable `update.json`; prerelease builds use GitHub's public Releases API to find the newest published testing or stable manifest. Drafts and foreign manifest URLs are rejected. It downloads only when asked, checks SHA-256, package ID, increasing version code, and the installed signing identity, then hands off to Android’s installer. Android may require enabling “install unknown apps” separately for Spare, even if it is already enabled for Downloader. Prereleases remain excluded from stable update discovery. Version 0.1.0 needs one manual update to 0.1.1 to fix testing-channel discovery.

## Build and verify

Use JDK 17 or 21 and Android SDK 36. The checked-in Gradle wrapper downloads the pinned build tool and checks its SHA-256. Configure `ANDROID_HOME` or an ignored `local.properties` file with `sdk.dir`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Production dependencies are locked in `app/gradle.lockfile`. Deliberate dependency updates must refresh that file with `--write-locks` and pass checks. The unsigned optimized APK is built unless release-signing environment variables are configured. See [releasing](docs/RELEASING.md) and [device testing](docs/TESTING.md).

## Privacy and security

Sources, credentials, and the guide cache are encrypted with an app-specific Android Keystore key in private storage; cloud backup/device transfer is disabled. Favorites, last-channel IDs, quality observations (up to 5,000 channels), temporary unavailability, guide video style, and guide preferences stay in private preferences. Removing a source removes its cached guide and preferences. Uninstalling clears local app data.

Provider traffic, guide downloads, and logos contact the URLs supplied by your provider. Update checks and APK downloads contact GitHub (including api.github.com for prerelease discovery). There is no Spare credential service or telemetry endpoint. Provider data never becomes HTML or executable instructions.

HTTPS is the default. HTTP requires explicit consent for each source and exposes data in transit. Every media manifest, segment, key, subtitle, image, and guide request is restricted to HTTP/HTTPS and the source’s HTTP policy; file/content/executable schemes are rejected. HTTP redirect downgrades are rejected for HTTPS-only sources. Imports, decompressed data, XML nesting, logos, and field lengths have size limits; DTDs and custom XML entities are rejected. Import work runs off the UI thread. App and Media3 release logging omit provider credentials and URLs.

Report security issues using the process in [SECURITY.md](SECURITY.md). See [third-party notices](THIRD_PARTY_NOTICES.md).
