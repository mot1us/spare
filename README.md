# Spare

**macOS preview:** Live TV and Sports on Apple Silicon and Intel. Download the Mac ZIP from [Releases](https://github.com/mot1us/spare/releases); see [Mac setup and controls](macos/README.md).

A minimal, free, open-source IPTV player for Android TV. Native, lightweight, and built for a remote. MIT licensed.

## Features

- M3U and Xtream Codes playlists enabled together, with collapsible provider categories and shared Search/Sports.
- Live TV guide, categories, favorites, and shared text/voice search across channels, movies, series and known episodes.
- Local guide tab combining up to eight cities/states, with suggestions and cached station matches.
- Live preview or a guide with adjustable opacity and programme artwork from your provider or optional metadata.
- Switchable frame-rate matching, audio tracks, and per-channel subtitles.
- Sports overlay through OK → Sports overlay, showing today’s live and final scores, automatically pinned until closed, with top/bottom placement; Up focuses the bar, Left/Right browses, and OK tunes a game. RedZone tiles autoplay; hold OK for stream choices.
- Optional automatic recovery switches a buffering game to another matched feed; configure it under Playback.
- Sports stream selection by resolution first, with known FPS as a tie-breaker. FPS is learned during playback without extra stream probes; all qualities remain playable.
- Adjustable Sports score delay with per-channel overrides, so scoreboard updates can follow your stream’s latency.
- Sports hub with Today/Live/calendar, scores, followed teams, reminders, best-stream playback and provider catch-up. NFL, MLB, NBA, NHL, Premier League, MLS, Champions League, NCAA football (FBS/FCS), men's/women's basketball, and college baseball.
- Provider movies and series, a Watchlist with new/unwatched episodes, and Continue watching. Channel catch-up is in the Guide.
- Watch VOD from beginning from a live programme's details; movies and identified episodes start from zero.
- Live theme editor with a guide preview, custom colors, offline fonts, layout styles, density, spacing and opacity; a retro Nostalgia style, 12/24-hour time, and optional AIO Metadata.
- Cached startup, playback-aware playlist/EPG refresh, and cached logos/artwork. VOD catalogs load when opening Library/Search.
- In-app signed updates. No ads, analytics, or Spare account.

Spare includes no channels or subscriptions. Provider features and device support vary.

Search, Guide, Sports, Library and Settings share a left dock. Right returns to current content; OK opens an item. Settings → Guide controls category highlighting, preview-first playback, list looping and the hidden/always-open dock.

## Remote controls

| Where | Control | Action |
| --- | --- | --- |
| Watching live | Up / Down | Change channel |
| Watching live | OK | One row of icon actions, programme details, and recent channels |
| Watching live | Left | Previous watched channel |
| Watching live | Back | Open guide |
| Guide | OK | Full screen, or preview first when configured |
| Guide | Back | Categories, then main menu, then full screen |
| Guide | Hold OK | Toggle favorite |
| Guide | Hold Back | Full screen |
| Guide | Left / Right | Categories or programme times |
| Category sidebar | Left / Back | Open main menu |
| Movie / episode | Back / Stop | Stop and return to Library |
| Catch-up | Back | Return to the live channel |
| Library | Library action during playback | Pause and browse |

On your first Sports visit, choose leagues. The left rail browses one league at a time. The schedule picker offers Today, Live now and Choose date for that league. Library uses the same rail for Movies, Series, Watchlist and Continue watching, with a category picker above the catalog. Right enters the grid and Back returns to the selected rail item. Left from the first card column reaches the league rail; Up from its heading reaches Sports settings. Each league remembers your position. Configure or reset leagues, teams, reminders and spoilers in Sports settings. Select a card for live, replay or reminder actions; NFL includes a RedZone destination matched to your playlist. Background refresh schedules and last-success status are under Settings → Playlists → Refresh settings. Intervals follow Android’s background limits. Choose a game in Sports to find streams. Use Add screen or playback details → Multiview for two-up/four-up viewing; the account and device must support the selected stream count. Recent-channel tiles in playback details offer Watch or Add screen. Focus a pane for audio; OK makes it full screen. Player, tracks and subtitle controls are grouped under Settings → Playback. Settings → Theme previews and saves appearance changes immediately. Sports refreshes automatically and checks supported catch-up archives in the background.

## Install

Download **spare.apk** from [Releases](https://github.com/mot1us/spare/releases), sideload it, then add your playlist in **Settings → Playlists**. Enable HTTP there only if your provider requires it.

Updates appear inside Spare. Android may require permission to install apps or an installation confirmation. Current builds are prereleases for testing on the onn 4K Pro.

[Documentation and architecture](docs/README.md) · [Build and device testing](docs/TESTING.md) · [Releasing](docs/RELEASING.md) · [Security](SECURITY.md) · [Changelog](CHANGELOG.md)
