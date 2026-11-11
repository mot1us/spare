# macOS preview contracts

## Scope

The native macOS preview provides Live TV and Sports through SwiftUI and one dynamically loaded VLC player. It does not open background video probes or add Android-only Library, catch-up, or multiview features.

## Provider and guide data

Playlist credentials belong in Keychain. Lineup and guide snapshots are encrypted with a Keychain-held key. Synthetic tests use an isolated directory and an in-memory key store.

Each encrypted snapshot also records the playlist identity that produced it: ID, kind, address, account credentials, guide override, and HTTP consent. Restore requires an exact identity match, so editing an account, address, or HTTP consent cannot reopen the old account's URLs after a failed refresh. The display name is excluded; renaming preserves offline cache access and current playback. Identity is inside ciphertext rather than a credential-derived filename. Legacy unscoped caches are ignored and refreshed once because their ownership cannot be established safely.

Provider requests have response-size and time limits. HTTP requires consent on the selected playlist, including metadata redirects. A failed optional Xtream category request must not prevent a valid live-stream list from loading; those channels use the Other group. Authentication and stream-list failures remain errors.

XMLTV accepts bounded plain or gzip-compressed XML. It rejects DTD and entity declarations, including declarations encoded as UTF-16 or UTF-32, and never resolves external entities. Normal predefined XML escapes remain valid.

## Playlist lifecycle

Changing playlists cancels lineup, guide, matching, filtering, and tuning work associated with the old source. Old results must not change the new source's loading state, guide, stream choices, or playback. Previously displayed channels and search indexes must not remain selectable under a different source's HTTP consent or credentials. Refreshing the same source may retain its working channels and current playback.

Visible-channel EPG workers belong to one playlist generation. Cancelling a worker releases its slot immediately, and an old worker cannot clear the replacement worker's slot. Deleting the active source applies the same cleanup before opening a remaining source.

## Sports refresh

Failed scoreboard requests retain the last successful games for the same league and requested date, while reporting the failed league. Failure does not renew the cache timestamp or borrow another date's schedule. Non-final cached games are marked Saved and excluded from Live filtering/actions until a successful refresh; reusing the cache does not clear that stale marker. Known final results remain Final, with existing score-hiding rules. When adjacent date responses overlap, a successful current response takes precedence over a stale copy. A focused hero tracks the selected game's ID and resolves its current scores/status from the latest visible snapshot. Current league/live filters and score-hiding preferences continue to apply.

## Verification and limits

Core regressions cover import, XML bounds, encrypted persistence, and Sports matching. Native smoke checks use synthetic sources and isolated preferences/data. The app uses one serial VLC worker; real-provider codec behavior, HDMI refresh matching, and production Keychain prompts require device testing.

The initial media URL is consent-checked, but VLC owns subsequent media redirects and HLS segment requests. The current bridge cannot enforce the playlist's HTTP consent on those internal requests. Full transport enforcement remains an open security limitation and must not be described as complete.

Artwork remains a separate resource-limit follow-up: disk files have no total size or expiry bound, disk-cache hits bypass the in-memory entry trim, and `NSImage` decodes source-size images rather than bounded thumbnails. Per-download byte limits do not bound decoded image memory or accumulated cached bytes.

Playback gives each buffering episode a fresh twenty-second grace period. A frozen playing clock is bounded separately at twenty-five seconds. Resume resets those clocks so time spent intentionally paused cannot trigger recovery. Exhausting recovery stops the VLC input and releases its provider connection.

## Change notes

- 2026-11-10: Recorded import failure isolation, encoded XML declaration rejection, playlist-generation ownership, buffering recovery, and the current media transport limitation while reviewing the preview.
