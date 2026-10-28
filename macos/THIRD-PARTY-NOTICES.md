# Playback runtime

Spare's own source is MIT licensed. The macOS preview dynamically loads unmodified
VideoLAN VLC 3.0.23 libraries and plugins, with their own LGPL/GPL and third-party
licenses. These libraries are separate from the Spare executable and remain
replaceable in Spare.app/Contents/Frameworks/VLCRuntime.

- Upstream: https://www.videolan.org/vlc/
- Runtime: https://download.videolan.org/pub/videolan/vlc/3.0.23/macosx/vlc-3.0.23-universal.dmg
- Matching VLC source: https://download.videolan.org/pub/videolan/vlc/3.0.23/vlc-3.0.23.tar.xz
- Build recipes and dependency source references: the contrib directory in that source archive.
- Upstream contributed source mirror: https://download.videolan.org/pub/videolan/contrib/
- libvlc documentation: https://www.videolan.org/vlc/libvlc.html

The matching VLC source archive accompanies the Mac download in GitHub Releases.
VLC's COPYING, COPYING.LIB and AUTHORS files accompany the application. Nothing
in Spare's MIT license replaces the licenses of the bundled playback components.
Library replacement and debugging are permitted. For a modified local runtime,
rebuild with scripts/macos-build.py --vlc-app /path/to/VLC.app and ad-hoc sign the
result; the preview imposes no Developer ID library validation.

Sports scores and team artwork are obtained from ESPN's public endpoints. ESPN
and team marks belong to their owners. Spare is not affiliated with ESPN or the
leagues, and does not provide or sublicense television streams.
