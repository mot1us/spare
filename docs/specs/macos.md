# macOS preview contracts

## Scope

The native macOS preview provides Live TV and Sports through SwiftUI and one dynamically loaded VLC player. It does not open background video probes or add Android-only Library, catch-up, or multiview features.

The deployment target is macOS 13. The default distribution is universal for Apple Silicon and Intel. [Mac setup](../../macos/README.md) describes installation and controls; [updates and release](updates-and-release.md) describes packaging and publication boundaries.

## Ownership and interfaces

| Component | Responsibility |
| --- | --- |
| `SpareCore.PlaylistService` | Validate source addresses, import M3U/Xtream lineups, construct playback candidates, and fetch short EPG. |
| `SpareCore.GuideService` | Decode bounded XMLTV/gzip data into programmes keyed by provider guide ID, falling back to channel ID. |
| `SpareCore.Vault` | Serialize Keychain access and authenticated disk-cache reads/writes. |
| `SpareCore.SportsService` / `SportsMatcher` | Fetch public league schedules and rank channels using the imported lineup and guide. |
| `SpareMac.AppStore` | Own main-actor UI state, source/task generations, filtering, selected game, and one `Player`. |
| `SpareMac.Player` / `CSpare` | Serialize blocking VLC operations and bind one video surface; dynamically load the bundled runtime. |

`Playlist` owns a saved ID, kind, display name, address, credentials, guide override and HTTP consent. `Lineup` owns channels, its guide address and save time. Channels retain stream/guide IDs and quality evidence; programmes carry a start/end interval and text. Mac IDs and persistence are local to this implementation; Android files are not a shared cache format.

## Provider and guide data

Playlist credentials belong in Keychain. Lineup and guide snapshots are encrypted with a Keychain-held key. Synthetic tests use an isolated directory and an in-memory key store.

Each encrypted snapshot also records the playlist identity that produced it: ID, kind, address, account credentials, guide override, and HTTP consent. Restore requires an exact identity match, so editing an account, address, or HTTP consent cannot reopen the old account's URLs after a failed refresh. The display name is excluded; renaming preserves offline cache access and current playback. Identity is inside ciphertext rather than a credential-derived filename. Legacy unscoped caches are ignored and refreshed once because their ownership cannot be established safely.

Provider requests have response-size and time limits. HTTP requires consent on the selected playlist, including metadata redirects. A failed optional Xtream category request must not prevent a valid live-stream list from loading; those channels use the Other group. Authentication and stream-list failures remain errors.

XMLTV accepts bounded plain or gzip-compressed XML. It rejects DTD and entity declarations, including declarations encoded as UTF-16 or UTF-32, and never resolves external entities. Normal predefined XML escapes remain valid.

The HTTP client uses ephemeral sessions without shared cookies, URL credentials or URL caching. It rejects user-info URLs and addresses longer than 16,384 characters, bounds bytes as chunks arrive, and validates redirects against the same consent. Artwork is HTTPS-only even when the provider has HTTP consent. Errors shown to users use safe categories rather than raw credential-bearing request URLs.

| Resource | Current bounds |
| --- | --- |
| Live lineup | 48 MiB response; at most 100,000 channels; M3U lines at most 32,768 characters. |
| Xtream account / categories | 2 MiB / 4 MiB responses; categories are optional labels. |
| Full XMLTV | 64 MiB download, 96 MiB expanded XML, 500,000 accepted programme records and 512 retained entries per channel. |
| Short EPG | 2 MiB response, eight-second request timeout, at most 72 returned entries parsed. |
| Encrypted cache read | 128 MiB file limit, authentication before decoding. |
| Scoreboard | 3 MiB response, eight-second request timeout, at most 500 events per response. |

Full guide parsing keeps programmes ending no more than three days ago and starting no more than eight days ahead; invalid/reversed intervals are skipped. The default metadata request timeout is 25 seconds with a resource timeout twice that; full XMLTV uses 40 seconds. These are transport budgets, not promises that parsing or UI work finishes within the same interval.

The Vault uses AES-GCM with a Keychain-held 256-bit key, atomic writes, owner-only cache files, and no plaintext fallback. UserDefaults holds ordinary UI choices such as active source ID, favorite channel IDs, leagues, followed team IDs, score hiding and appearance; credentials remain in Keychain. Artwork has a separate cache and is not covered by the encrypted lineup/guide contract.

## Playlist lifecycle

Changing playlists cancels lineup, guide, matching, filtering, and tuning work associated with the old source. Old results must not change the new source's loading state, guide, stream choices, or playback. Previously displayed channels and search indexes must not remain selectable under a different source's HTTP consent or credentials. Refreshing the same source may retain its working channels and current playback.

Visible-channel EPG workers belong to one playlist generation. Cancelling a worker releases its slot immediately, and an old worker cannot clear the replacement worker's slot. Deleting the active source applies the same cleanup before opening a remaining source.

Startup restores the selected source's matching lineup before its guide. A saved lineup older than 24 hours starts a background refresh; a fresher lineup can load its guide without redownloading channels. Failed same-source refreshes retain usable existing data. Visible Xtream guide rows warm at most two channels concurrently, with a five-minute recheck interval and only when less than an hour of guide coverage remains. Filtering runs away from the main actor and publishes only the latest generation.

Saving or deleting a source must first save the updated playlist list securely. A failed Keychain write reports a storage error instead of publishing an unsaved source list. Source deletion cancels active writers before deleting its lineup and guide caches. Closing the last window terminates the application; this preview does not promise background viewing after all windows close.

## Playback lifecycle

Browsing Live TV, Sports and full screen reuses the same player. Rapid tunes invalidate older queued commands. An explicitly selected channel can supply alternate formats or matched channels, but the player retains at most six candidate URLs and plays them sequentially. It does not test candidates by opening additional players. Measurements come from current playback and update in-memory channel quality; unknown FPS remains playable.

The serial VLC worker handles open, stop, reconnect and track operations. Polling coalesces outstanding work and rejects snapshots from an older tune generation. A missing runtime produces a reinstall message. Recovery retries or advances through the bounded candidate list and reports unavailable when exhausted; it must stop the VLC input at that point to release the provider connection.

Each buffering episode gets a fresh twenty-second grace period. A frozen playing clock is bounded separately at twenty-five seconds. Intentionally paused playback does not trigger recovery; resume resets the watchdog clocks. These policies are separate from Android Media3 recovery.

## Sports refresh

Sports supports the seven leagues declared by `League.all`: NFL, MLB, NBA, NHL, Premier League, MLS and Champions League. Requests go to public ESPN scoreboard endpoints with league/date parameters, never playlist details. Selected local dates are mapped to the corresponding Eastern-time scoreboard dates, deduplicated by game ID, then filtered back to the requested local day. Successful league/date snapshots are reused for sixty seconds; the visible Sports screen refreshes every minute when a refresh is not already running.

Failed scoreboard requests retain the last successful games for the same league and requested date, while reporting the failed league. Failure does not renew the cache timestamp or borrow another date's schedule. Non-final cached games are marked Saved and excluded from Live filtering/actions until a successful refresh; reusing the cache does not clear that stale marker. Known final results remain Final, with existing score-hiding rules. When adjacent date responses overlap, a successful current response takes precedence over a stale copy. A focused hero tracks the selected game's ID and resolves its current scores/status from the latest visible snapshot. Current league/live filters and score-hiding preferences continue to apply.

Matching uses current programme and channel names, excludes replay/news-style guide titles from live matchup evidence, and distinguishes confirmed matchup evidence from possible team/network coverage. Confirmed coverage sorts first, then resolution, confidence and FPS. At most 100 matches are returned. A schedule/network match does not establish provider entitlement or prove that the stream carries the game.

## Verification and limits

`SpareCoreTests` cover import, HTTP bounds/redirect validation, XML parsing, encrypted persistence/cache identity and Sports matching/refresh failures. `SpareMacTests` cover source task ownership, rename continuity, current hero resolution and playback watchdog behavior. Native smoke checks use synthetic sources and isolated preferences/data. `--demo` uses a dedicated preferences suite and in-memory secrets; `SPARE_DATA_DIR` alone changes the Vault location, not production Keychain ownership. Tests should supply their own preferences and Vault, or use demo mode, to avoid personal data.

Required commands and hardware limits are in [Testing](../TESTING.md). Swift tests do not prove runtime packaging or native video rendering: build with `scripts/macos-build.py`, then perform the isolated native smoke check. Real-provider codec behavior and production Keychain prompts require device testing. Display-mode switching is outside the current Mac scope.

The initial media URL is consent-checked, but VLC owns subsequent media redirects and HLS segment requests. The current bridge cannot enforce the playlist's HTTP consent on those internal requests. Full transport enforcement remains an open security limitation and must not be described as complete.

Artwork remains a separate resource-limit follow-up: disk files have no total size or expiry bound, disk-cache hits bypass the in-memory entry trim, and `NSImage` decodes source-size images rather than bounded thumbnails. Per-download byte limits do not bound decoded image memory or accumulated cached bytes.

## Change notes

- 2026-11-10: Recorded import failure isolation, encoded XML declaration rejection, playlist-generation ownership, buffering recovery, and the current media transport limitation while reviewing the preview.
- 2026-11-10: Added component ownership, data and request bounds, startup/cache lifecycle, preview-specific Sports behavior, playback ownership and test-isolation requirements from the existing implementation.
