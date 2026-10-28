# Spare for macOS — preview

Native SwiftUI app for macOS 13+, Apple Silicon and Intel. Live TV and Sports:
M3U/Xtream playlists, cached guide, provider groups, favorites, text search,
audio/subtitle tracks, schedules/scores, followed teams and resolution-first
stream matching. One playback connection; no background video probes.

Download the Mac ZIP from the same [Releases](https://github.com/mot1us/spare/releases)
as the Android APK, unzip, and move Spare to Applications. This preview is ad-hoc
signed, not notarized: after the first launch is blocked, open **System Settings →
Privacy & Security → Open Anyway** for Spare. No VLC installation is required.

Add your playlist from the toolbar or **⌘O**. Clicking a channel tunes the preview;
double-click the video or use **⌃⌘F** for full screen. **⌘←** returns to the previous
channel. **⌘1/⌘2** switch between Live TV and Sports. Right-click a Sports card to
follow its teams. Settings include enabled leagues and score hiding.

Playlists are in Keychain. Lineup and guide caches are encrypted in
~/Library/Application Support/Spare; artwork is cached separately. HTTP needs
per-playlist consent. Sports never sends playlist details to the score service.

This first preview does not include the TV app's Library, catch-up, multiview,
voice search, display-mode switching, full theme editor or in-app Mac installer.
Use GitHub Releases for Mac updates during preview testing.

Build on a Mac with Xcode's command-line tools:

    cd macos
    swift test
    cd ..
    python3 scripts/macos-build.py

Builds download a checksum-pinned official VLC runtime and matching source.
Read [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for runtime licenses.
