# Spare

**macOS preview:** Live TV and Sports on Apple Silicon and Intel. Download the Mac ZIP from [Releases](https://github.com/mot1us/spare/releases); see [Mac setup and controls](macos/README.md).

A minimal, free, open-source IPTV player for Android TV. Native, lightweight, and built for a remote. MIT licensed.

## Features

- M3U playlists and Xtream Codes accounts; multiple saved playlists.
- Live TV guide, categories, favorites, and shared text/voice search across channels, movies, series and known episodes.
- Local guide tab combining up to eight cities/states, with suggestions and cached station matches.
- Live preview or a guide with adjustable opacity and programme artwork from your provider or optional metadata.
- Switchable frame-rate matching, audio tracks, and per-channel subtitles.
- Sports stream selection by resolution first, with known FPS as a tie-breaker. FPS is learned during playback without extra stream probes; all qualities remain playable.
- Sports hub opening on Today, with illustrated game cards, a card grid, featured game artwork, live/final scores, followed teams, saved game reminders, Watch best stream selection, completed-game catch-up and optional hidden final scores. NFL, MLB, NBA, NHL, Premier League, MLS and Champions League.
- Provider movies and series, a Watchlist with new/unwatched episodes, and Continue watching. Channel catch-up is in the Guide.
- Watch VOD from beginning from a live programme's details; movies and identified episodes start from zero.
- Live theme editor with a guide preview, custom colors, offline fonts, layout styles, density, spacing and opacity; a retro Nostalgia style, 12/24-hour time, and optional AIO Metadata.
- Saved startup, background playlist/EPG refresh schedules, and cached Sports artwork.
- In-app signed updates. No ads, analytics, or Spare account.

Spare includes no channels or subscriptions. Provider features and device support vary.

Search, Guide, Sports, Library and Settings share a left dock. Right returns to current content; OK opens an item. Settings offers a hidden or always-open dock.

## Remote controls

| Where | Control | Action |
| --- | --- | --- |
| Watching live | Up / Down | Change channel |
| Watching live | OK | Details, recent channels, and other streams for the current game |
| Watching live | Left | Previous watched channel |
| Watching live | Back | Open guide |
| Guide | OK | Watch selected channel full screen |
| Guide | Hold OK | Toggle favorite |
| Guide | Hold Back | Full screen |
| Guide | Left / Right | Categories or programme times |
| Category sidebar | Left | Jump to Search / Settings |
| Movie / episode | Back / Stop | Stop and return to Library |
| Catch-up | Back | Return to the live channel |
| Library | Library action during playback | Pause and browse |

Sports opens on Today. Live shows current games across your enabled leagues; Calendar selects another date. Configure or reset leagues, teams, reminders and spoilers in Sports settings. Select a card for live, replay or reminder actions; NFL includes a RedZone destination matched to your playlist. Background refresh schedules and last-success status are under Settings → Playlists → Refresh settings. Intervals follow Android’s background limits. Choose a game in Sports to find streams. Use Add screen or playback details → Multiview for two-up/four-up viewing; the account and device must support the selected stream count. Recent-channel tiles in playback details offer Watch or Add screen. Focus a pane for audio; OK makes it full screen. Player, tracks and subtitle controls are grouped under Settings → Playback. Settings → Theme previews and saves appearance changes immediately. Sports refreshes automatically and checks supported catch-up archives in the background.

## Install

Download **spare.apk** from [Releases](https://github.com/mot1us/spare/releases), sideload it, then add your playlist in **Settings → Playlists**. Enable HTTP there only if your provider requires it.

Updates appear inside Spare. Android may require permission to install apps or an installation confirmation. Current builds are prereleases for testing on the onn 4K Pro.

[Build and device testing](docs/TESTING.md) · [Releasing](docs/RELEASING.md) · [Security](SECURITY.md) · [Changelog](CHANGELOG.md)
