# Android Library, Search, and provider catch-up

## Scope and owned data

Library provides provider movies, series, episodes, Watchlist, and Continue
watching. Search combines these titles with live channels and current/upcoming
programme matches across enabled playlists. Provider catch-up is entered from the
Guide and remains separate from movie/series catalogs. These features do not
record streams, maintain a timeshift buffer, or supply content. They are outside
the current [macOS preview](macos.md).

`OnDemand` describes a movie, series, episode, or catch-up item, including its
provider ID, playlist owner, display metadata, and playback URL where applicable.
Xtream IDs use distinct `movie:`, `series:`, and `episode:` prefixes. Episodes keep
their parent series, season, and episode numbers. A series is a browsable parent,
not a playable stream. `WatchProgress` records an item, position, duration, and
last-updated time. `LibraryFavorites` owns saved movies/series and watched episode
IDs. Sensitive persistence follows [Android provider storage](android-provider-storage.md).

`VodCatalog` reads provider metadata; `LibraryCatalog` shares encrypted catalog
indexes/imports; `LibraryController` owns browsing, detail/episode requests, and
progress selection. `PlaylistSearch` searches provider catalogs, while
`PlaylistLiveSearch` searches the current merged guide. `MainActivity` hands a
selected item to the existing primary player under the [playback contract](playback.md).

## Catalog loading and bounds

- Xtream movies and series use separate endpoints. Movie playback uses the
  validated provider direct URL or a standard encoded route; selecting a title
  resolves one file and does not probe alternate formats. Episode lookup is
  explicit for a selected series and sorts results by season/episode.
- M3U movie browsing uses entries marked as media. M3U does not provide Xtream
  series enumeration. Its imported media entries can also appear in Search's live
  result section; they have no live programme match.
- Library/Search load catalogs on demand. Startup and a missing live-programme VOD
  hint must not automatically download the full VOD catalog. Already restored
  catalog indexes can answer those hints without a network request.
- Category browsing can load a single provider category. Explicit playlist-wide
  search uses full movie/series indexes and reuses shared imports. Missing category
  metadata falls back to bulk catalog browsing. A complete, usable catalog younger
  than six hours can be reused unless refresh is explicitly requested.
- Streaming imports accept at most 100,000 records, publish a small initial batch
  followed by batches of at most 64, and retain compact searchable/display fields.
  Lean full-catalog responses are capped at 64 MiB; non-lean responses at 24 MiB.
  Unknown JSON fields are skipped. Partial results are useful but are never marked
  as a complete provider inventory.
- The encrypted index owns chunks of at most 512 entries. Current compact indexes
  can reconstruct standard movie URLs from the source instead of repeating
  credentials in every title. Legacy encrypted pages remain readable; disk restore
  bounds the index to 2,000 chunks and page reads to 512 records.
- Two shared import workers serve catalog consumers. Identical work coalesces;
  leaving a Search surface may let useful metadata work finish, while source
  invalidation cancels producers and rejects obsolete partial/final publication.
  UI query changes cancel obsolete reads without restarting a shared download.
- A failed refresh keeps useful saved data and displays a retry state. A partial,
  absent, or failed index does not prove a title is absent from the provider.

Series info is capped at an 8 MiB response, 1,000 season groups, and 20,000 parsed
episodes. The browsing memory cache retains at most five series, each with at most
2,000 episodes. These bounds do not imply that every episode is globally searchable.

## Search and live-programme VOD matching

Search supports All, Live TV, Movies, Series, and Episodes filters. Blank text does
not publish title results. Text normalization is shared with channel matching;
voice input supplies editable text to the same flow. Episode results come from
episodes already opened or retained in viewing history, not a provider-wide crawl
of every series.

Catalog search returns at most 40 matches per kind per provider, then deduplicates
by playlist and item ID with an overall 120-title cap. Live search has its own
40-results-per-provider and 120-result total caps. It ranks exact current programme
titles, exact upcoming titles, channel/category names, and partial programme
matches in that order. Upcoming programme matching is limited to three hours.
Identical provider-local IDs from different playlists remain distinct selections.

The live programme's “Watch from beginning” action uses `VodStart` to match an
exact normalized movie/series title and compatible release year in saved indexes.
Quality variants of one title may select a preferred variant; remakes or
movie/series ambiguity require a choice. An identified episode requires one exact
season/episode match from its series. Movies and resolved episodes start at zero.
This action must never substitute a catch-up or guessed timeshift URL.

`VodAvailability` bounds the “Checking for VOD” state to eight seconds for a pending
incomplete catalog. A complete index can report no match immediately. An unfinished
or failed import reports that limitation rather than claiming the provider lacks
the title. Missing child media resources do not suppress a catalog title; endpoint
suppression follows the shared [missing-media rule](playback.md).

## Viewing progress and Watchlist

Movie/episode progress is local, encrypted, and scoped to provider account identity.
The player periodically saves progress during playback and saves on relevant
navigation/lifecycle transitions. Continue watching retains at most 100 items,
requires at least ten seconds of progress, and removes ended titles or titles
within the final 30 seconds of a known duration. Catch-up does not become a movie
or episode resume record. Seeking is available only when the media is seekable.

Watchlist accepts at most 200 movies/series. An initial episode check establishes a
baseline; later additions count as new until watched. Removing a saved series does
not erase completed episode IDs. Per-series episode/new sets retain at most 5,000
IDs; the watched set retains at most 10,000. A refresh checks up to 24 due series,
normally no more often than every 15 minutes, using a 20-second scheduling window
and bounded individual provider calls. Provider failures leave prior state intact.

Back/Stop from a movie or episode stops playback and returns to Library. The
Library action during playback pauses the same player for browsing. Back from
catch-up returns to the live channel. Browsing and search must not create a second
video player or steal source ownership from the selected item.

## Advertised provider archives

`Catchup` requires known archive metadata, a positive advertised duration, and a
supported mode (`xc`, `default`, `append`, or `shift`). Template modes also require
an explicit template. Capability inventory includes all advertised channels,
independent of category/guide filters. Old cached duration fields alone do not
establish archive support.

A replay must have ended, fall inside the advertised window, and last no more than
24 hours. Xtream archive URLs use encoded provider credentials, UTC start time,
and rounded-up minute duration. M3U templates substitute only supported placeholders;
unresolved braces or disallowed URLs reject the replay. Live URLs are not guessed
into archives for unadvertised channels.

Xtream archive listing tries `get_simple_data_table`, then the legacy spelling
`get_simple_date_table` when the first fails or is empty. Both share one deadline;
a working first response avoids a second request. Each response is bounded to
4 MiB and parsed by `XtreamEpg`. Saved guide history remains usable on failure.

## Optional descriptive metadata

An explicitly configured HTTPS Stremio-compatible manifest may enrich titles and
programme artwork. It receives a normalized title or external metadata ID, never
provider credentials or playback URLs, and cannot replace the selected provider
stream. Release year constrains local matching. Each metadata search consults at
most two compatible catalogs per media type; live-programme enrichment checks
both movie and series. Responses and results remain bounded; ambiguous matches
leave provider metadata intact. Configuration, cached metadata,
and provider ownership remain separate from playback authority.

## Verification and limits

`OnDemandTest`, `CatchupTest`, `LibraryFavoritesTest`, `VodStartTest`,
`VodAvailabilityTest`, `PlaylistLiveSearchTest`, and `RefinementTest` cover pure
contracts under [`app/src/test`](../../app/src/test/java/io/github/mot1us/spare/).
The isolated device suite exercises catalog reuse, combined search, exact episode
start, encrypted/account-scoped viewing history, missing media resources, and the
shared player through `guideAndLibraryShareSearchAcrossChannelsMoviesSeriesAndKnownEpisodes`,
`liveProgrammeCanStartItsVodMovieOrIdentifiedEpisodeWithoutUsingCatchup`,
`continueWatchingPositionsAreEncryptedScopedAndClearedWhenFinished`,
`missingVodSegmentsKeepTheTitleWhileMissingEndpointsHideIt`, and
`libraryMoviesSeriesArchiveAndFontUseSamePlayerAndReturnToLive`.
Use the [testing workflow](../TESTING.md) for changes to these behaviors.

Provider catalog completeness, archive availability, seek support, and metadata
accuracy remain external limitations. No finite Search result cap or saved index
establishes an exhaustive current provider inventory.

## Change notes

- 2026-11-10: Established the existing Library, Search, history, and provider
  catch-up contracts from implementation and regression coverage.
