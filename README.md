# Spare

A minimal, free, open-source IPTV player for Android TV.

Spare plays your own live-TV sources. It includes no channels, subscriptions, advertisements, analytics, or app account. Current prerelease: **0.1.2**. License: MIT.

## What it does

- M3U playlist URLs or Xtream Codes server/username/password.
- Several saved sources, one active at a time.
- Up to 100,000 channel entries per source, within bounded download sizes. Larger lists produce an explicit limit message.
- A restrained dark channel/time guide with channel logos, clear remote focus, and a small preview of the current channel.
- Provider groups, channel-name search, and Favorites; provider channel order is preserved.
- Automatic provider XMLTV guide or an optional XMLTV URL override; three days of guide data, cached privately.
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
| Guide | Left / Right | Move the three-hour guide window by 30 minutes |
| Guide | Back | Return to current channel |
| Full-screen player | Back | Exit app |

The Sources button adds, switches, edits, or removes sources. Playback contains audio/subtitle controls, guide refresh, and About. Text entry works with the TV keyboard and the paired Google TV phone remote; there is no custom phone pairing server.

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

Sources, credentials, and the guide cache are encrypted with an app-specific Android Keystore key in private storage; cloud backup/device transfer is disabled. Favorites and last-channel IDs stay in private preferences. Removing a source removes its cached guide. Uninstalling clears local app data.

Provider traffic, guide downloads, and logos contact the URLs supplied by your provider. Update checks and APK downloads contact GitHub (including api.github.com for prerelease discovery). There is no Spare credential service or telemetry endpoint. Provider data never becomes HTML or executable instructions.

HTTPS is the default. HTTP requires explicit consent for each source and exposes data in transit. Every media manifest, segment, key, subtitle, image, and guide request is restricted to HTTP/HTTPS and the source’s HTTP policy; file/content/executable schemes are rejected. HTTP redirect downgrades are rejected for HTTPS-only sources. Imports, decompressed data, XML nesting, logos, and field lengths have size limits; DTDs and custom XML entities are rejected. Import work runs off the UI thread. App and Media3 release logging omit provider credentials and URLs.

Report security issues using the process in [SECURITY.md](SECURITY.md). See [third-party notices](THIRD_PARTY_NOTICES.md).
