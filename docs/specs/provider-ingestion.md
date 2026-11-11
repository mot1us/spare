# Android provider ingestion and guide refresh

## Responsibility and interfaces

Spare imports user-supplied M3U or Xtream sources into a playable lineup and a
programme guide. It supplies no channels, provider accounts, or hosted proxy.
Multiple enabled sources may contribute to one Guide, Search, and Sports view.
The macOS implementation has a separate [preview contract](macos.md).

`Source` carries a saved source ID, kind, address, credentials, optional explicit
EPG URL, HTTP consent, enabled state, and configured stream limit. `Importers`
produces a `Lineup`; `FullEpg` and `XtreamEpg` produce `Programme` records.
`PlaylistRefresh` coordinates imports and returns a usable `Guide`, a sanitized
error message, and whether the lineup changed. It never touches a media player.
`MainActivity` owns publication into the active UI and playback state.

`PlaylistCatalog` scopes channel and programme keys by saved source ID while
retaining the original source's legacy keys. Native Xtream stream IDs and XMLTV
aliases remain available for provider calls. Equal stream IDs from different
sources must not merge their guides, quality evidence, favorites, or HTTP consent.
Account changes and cache ownership follow [Android provider storage](android-provider-storage.md).

## Import and transport contracts

- Provider network requests accept only URLs with a host, no embedded authority
  credentials, and an allowed HTTP scheme. HTTPS is the default; HTTP requires that source's explicit
  consent. Every redirect is checked again. Other URI schemes are rejected.
- The shared `Network` client bounds received and decompressed bytes, checks
  interruption, uses finite connect/read budgets, and allows at most six request
  attempts through redirects. Its default payload limit is 24 MiB. Streaming M3U
  parsing excludes parser work from the cumulative socket-read budget; this does
  not remove byte or socket-time limits.
- M3U requires an `#EXTM3U` header and at least one accepted stream. It handles BOM,
  CR/LF variants, quoted attributes, provider category order, and duplicate entries.
  Entries marked as media remain distinguishable from live channels. Unsafe logo
  and discovered EPG URLs are discarded rather than granting HTTP consent.
- M3U channel identity includes guide ID, title, host/path, and non-secret query
  parameters. Recognized rotating credential/signature query parameters do not
  change identity; meaningful channel query parameters do. This is identity
  normalization, not a license to log raw URLs.
- Xtream accepts a server base address, including normalization of known API
  filenames, but rejects base query strings and fragments. Credentials are encoded
  independently when constructing requests. Live stream IDs must be numeric and
  at most 20 digits. Optional category lookup failure leaves playable streams in
  fallback categories rather than rejecting the lineup.
- A malformed, unauthorized, inactive, empty, or oversized required response is an
  import failure. Do not replace a usable saved lineup with an empty response or
  report account failure as an empty successful catalog.

The principal input bounds are 100,000 live entries, 32,768 characters per M3U
line, and 20,000 Xtream categories. Display fields have separate truncation bounds
in the parsers. Provider limits are safety ceilings, not guarantees that every TV
can browse a maximum-sized provider equally quickly.

## Guide identity, time, and safety

`Programme` owns a channel key, title, start/end epoch milliseconds, description,
and optional artwork. A programme is current when `start <= now < end`. Invalid
dates or non-positive intervals are discarded. XMLTV accepts explicit offsets
and supported UTC forms; an omitted XMLTV offset means UTC. Xtream short EPG uses
validated numeric epoch timestamps and does not guess an offset for textual dates.

The default Xtream guide keeps stream identity separate from XMLTV alias identity.
Full XMLTV maps an alias to every corresponding stream; an explicit user EPG URL
uses the declared guide mapping. Legacy short-EPG migration must not copy an
ambiguous shared alias onto several different streams.

XMLTV may be plain or gzip. Decoded XML is limited to 64 MiB, two million start
elements, depth 32, and 16,384 characters per accumulated field. DTD declarations
and custom entities are rejected; built-in and numeric character entities remain
valid. Unknown channel IDs are ignored. Titles are retained up to 512 characters
and descriptions up to 2,048.

Guides retain programmes starting within the next three days and recent history:
normally one hour, or the channel's advertised archive window up to 30 days.
`ProgrammeBudget` caps retained records at 200,000. On overflow, a per-channel
window preserves current and upcoming programmes before older history so later
stations are not erased by earlier stations' large schedules. `GuideHistory`
merges incoming corrections by start time while pruning expired archive entries.
Archive history can still be limited by the provider and the record budget.

## Startup and refresh workflow

1. Bind the provider identity and complete any required invalidation on a worker.
2. Restore and publish the selected source's playable lineup before its larger
   programme cache. Restore additional enabled sources after the first lineup is
   applied. Keep loading visible until usable channels reach the UI.
3. Resume a recent/saved live channel at most once. User navigation or a new tune
   must not be overridden by a late restore. EPG-only merges preserve the latest
   lineup, focus, playback, and newer programme replies.
4. Refresh in the background according to independent playlist and EPG policies.
   Manual refresh and optional launch refresh are separate from interval settings.
   Supported intervals are Manual, 1, 3, 6, 12, 24, or 48 hours.
5. Prefer full XMLTV. When a default Xtream full guide is unavailable, use a bounded
   short-EPG fallback for recently used/favorite/seed channels, up to eight per
   refresh, while visible channels can fetch their own short EPG. An explicit
   custom EPG failure must not silently switch to a different mapping.

Foreground bulk refresh waits for playback warm-up and idle navigation. VOD,
multiview, Library, Search, and active Sports browsing defer it. Cache restore and
normal startup do not trigger VOD downloads or an eager per-channel EPG sweep.
Android `JobScheduler` owns closed-app interval timing and may defer it; initial
EPG seeding belongs to foreground warm-up before periodic jobs are configured.

`PlaylistRefresh` uses one shared background queue and coalesces only equal
`Source` snapshots. At most eight callbacks attach to one pending request. Source
edits/removal invalidate old publications; a saved ID alone does not establish
ownership. A failed playlist or EPG refresh retains usable saved data and reports
its error. EPG writes merge newer visible-channel data received during a download.
UI results must pass the current source/generation check before they are applied.

## Verification and limits

Parser and data contracts are exercised by `ImportersTest`, `NetworkDeadlineTest`,
`XtreamLineupTest`, `XtreamEpgTest`, `FullEpgTest`, `ProgrammeBudgetTest`,
`GuideHistoryTest`, `PlaylistCatalogTest`, and `RefreshPolicyTest` under
[`app/src/test`](../../app/src/test/java/io/github/mot1us/spare/).
The isolated device scenarios `savedStartupBackgroundRefreshAndScheduledJobKeepPlaybackAndFocus`,
`cachedStartupDoesNotDownloadVodOrSweepUnvisitedChannels`, and
`editedSourceRefreshDoesNotJoinThePreviousAccount` validate real activity ownership
and restoration. Run them through the [testing workflow](../TESTING.md) when
changing these behaviors.

Providers differ in category availability, XMLTV aliases, archive metadata, and
timestamp quality. Synthetic tests establish Spare's handling of those responses;
they do not establish a provider's availability or Android background scheduling
latency.

## Change notes

- 2026-11-10: Established the ingestion and refresh contract from the existing
  Android implementation and regression tests.
