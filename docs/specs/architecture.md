# Architecture and platform boundaries

## Purpose and scope

Spare is a native player for user-supplied IPTV sources. Android TV is the main
application; the separately approved macOS preview implements Live TV and Sports.
There is no Spare account, hosted credential service, advertising, or analytics.
Provider catch-up is remote archive playback, not local recording or timeshift.

Android supports M3U and Xtream live sources, provider movies and series, shared
Search, Sports, and explicit two-up/four-up multiview. macOS does not inherit the
Android Library, catch-up, or multiview scope. New product scope requires explicit
approval under [project instructions](../../AGENTS.md).

## Components and ownership

| Boundary | Responsibility and key entry points |
| --- | --- |
| Android app | `MainActivity` owns navigation, the primary Media3 player, source snapshots, and foreground lifecycle. Native Views and RecyclerViews implement the remote UI. |
| Provider data | `Importers`, `Network`, `XtreamEpg`, `FullEpg`, and `PlaylistRefresh` fetch and parse bounded data. `PlaylistCatalog` merges enabled sources without losing their ownership. |
| Persistence | `SecureStore` owns encrypted Android data and provider identity transitions. `GuideDisk`, Library pages, and image caches keep large data outside UI objects. |
| Library and Search | `LibraryCatalog`, `LibraryController`, and `PlaylistSearch` coordinate lazy catalog imports, browsing, history, and shared queries. |
| Sports | Schedule state is separate from provider coverage. Matching, replay discovery, score delay, reminders, and recovery have their own bounded work and cancellation. |
| Playback | `MainActivity` retains one primary player; `Multiview` owns up to three additional players only during an explicit session. Transport and recovery helpers do not open probe players. |
| Updates | `UpdateLookup`, `Release`, and `Updater` discover and verify Android packages. Installation uses native Android package installation. |
| macOS | `SpareCore` owns provider, HTTP, cache, and Sports services; `SpareMac` owns SwiftUI state and playback; `CSpare` dynamically loads the separate VLC runtime. |
| Delivery | Root `version.properties` supplies both platforms. Gradle builds Android; `scripts/macos-build.py` packages macOS. GitHub workflows own checked release publication. |

Source entry points are under `app/src/main/java/io/github/mot1us/spare/` and
`macos/Sources/`. The project intentionally uses a single Android app module and
a Swift package rather than a shared cross-platform UI or backend.

## Shared data contracts

- `Source` identifies a saved playlist and carries its type, provider address,
  credentials, HTTP consent, enabled state, and connection-limit preference.
  A saved source ID and a provider account identity are different concepts.
- `Channel` carries live/media classification, source ownership, provider and
  guide identifiers, catch-up declarations, and optional reported video quality.
  Merged identifiers must resolve back to their provider before requests or writes.
- `Guide` combines channels, category order, and programmes indexed by guide key.
  Programme times are epoch milliseconds; current-programme intervals include
  the start and exclude the end. A missing guide does not make a channel unplayable.
- Reported quality, measured playback quality, schedule state, and evidence of
  stream coverage remain distinct. A name or scoreboard alone cannot establish
  measured FPS or a working broadcast.

Detailed models and invariants belong to the linked domain specs in the
[documentation index](../README.md), rather than a parallel schema here.

## Lifecycle and asynchronous work

Android restores usable cached channels before optional EPG and Library work.
Opening the guide, changing appearance, and moving between preview and full
screen preserve the primary player and media attempt when playback continues.
Changing channels or playback mode may start a new media attempt on that player.

Background results must still belong to the active source/account and request
generation when published. Cancelling a task alone is not proof that its callback
or disk write cannot finish. Provider invalidation and producer publication obey
the [storage contract](android-provider-storage.md).

`ActivityWorkers` owns activity executors. Teardown interrupts cancellable work
while allowing the persistent cache-write executor to drain. `LatestTask` retains
one running and one latest pending snapshot instead of queueing every EPG write.
`onPause` stops primary audio first and releases additional multiview players;
`onStop` stops the primary player and foreground polling; `onDestroy` releases the
player, workers, image loaders, voice input, and update work. Returning after a
stop can reconnect; background/resume is not a playback-continuity promise.

## Trust boundaries and failures

Provider input is untrusted. Parsers retain input/item bounds and reject XML
DTDs and custom XML entities. Android provider requests and redirects obey the owning source's
HTTP consent; update transport always requires HTTPS. macOS VLC child-request
consent remains an explicitly documented limitation in the [Mac spec](macos.md).
Credentials, provider URLs, and viewing history remain local and protected as
specified in [Security](../../SECURITY.md).

An unavailable provider, image, EPG, scoreboard, or metadata service must not
silently become a successful empty replacement of usable saved data. Individual
domains define when cached data is stale, when retries are allowed, and when a
confirmed missing endpoint can be temporarily hidden. Synthetic tests do not
establish provider coverage, device decoder capacity, microphone support, or
physical HDMI refresh-rate behavior.

## Verification and known limits

Use [Testing](../TESTING.md) for commands and the physical-device checklist.
`ActivityWorkersTest`, `LatestTaskTest`, `SharedJobsTest`, and provider identity
tests cover ownership helpers; isolated Android scenarios cover lifecycle and
playback continuity. Swift tests cover core and app-state contracts separately
from packaged native smoke testing.

The [0.32.16 audit](../AUDIT-0.32.16.md#remaining-findings) records unresolved
Android M3U port-sensitive identity migration, Mac VLC child-request consent,
and Mac artwork memory/disk bounds. These remain follow-up work, not guarantees
created by this documentation review.

## Change notes

- 2026-11-10: Recorded existing ownership, platform boundaries, and lifecycle
  contracts, with links to the domain specifications.
