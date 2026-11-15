# Android Sports

## Purpose and scope

Sports combines public schedules and scores with coverage found in the user's
enabled playlists. A scheduled event is independent of whether a playable stream
exists. Sports never supplies subscriptions or stream URLs from a scoreboard,
and it does not open video connections to discover coverage or measure quality.
Playback and multiview remain subject to the [playback contract](playback.md);
provider ownership and encrypted files follow
[Android provider storage](android-provider-storage.md). The


The Android league registry covers NFL, MLB, NBA, NHL, Premier League, MLS,
Champions League, NCAA football (FBS/FCS), men's and women's basketball, and
college baseball. The first Sports visit requires league selection before
scoreboard loading. The league rail, Today/Live/date picker, followed teams,
reminders, stream choices, and completed-game replays are separate views of the
same event identities. Each league retains its browsing position. RedZone is a
playlist destination, not a fabricated scoreboard event.

Sports opens on Live now: live games from every chosen league, with no league
selected. Choosing a league shows that league's day (live, upcoming and
results); Today and Calendar use the last chosen league. A broadcaster's channel
offered as a possible stream is dropped when its own guide names a different
matchup at that time. OK on the RedZone card plays the best feed already known
rather than first asking provider guides for a sharper one.

Live now also lists guide coverage (`SportsGuideCoverage`): golf, tennis, motor
racing and fights airing now whose listing says "live" and names the sport
(title or description, or the channel name when the title names no other
sport). Studio shows, replays, previews and highlights are excluded; listings of
the same event are grouped into one card of channels, at most 24 cards. These
are guide evidence, not scoreboard events, and appear only on Live now.

Provider event slots (`EventChannelNames`): categories with three or more
numbered or "no event" channels are re-read from Xtream providers (categories
list plus `get_live_streams&category_id`, at most eight categories) every 10
minutes while Sports is open. Fresh names are used only for matching and stream
labels; the saved lineup and guide are unchanged until the next full refresh.

`scripts/sports_team_directory.py` captures ESPN's full team lists for the
`SportsTeamDirectoryAuditTest` report (developer use only; the app makes no such
requests). The test is skipped unless `SPARE_TEAM_DIRECTORY` names a capture.

## Owners and data contracts

| Owner | Responsibility |
| --- | --- |
| `SportsLeagues`, `SportsCatalog` | Supported competitions, stable team IDs, provider aliases, and public scoreboard paths. |
| `SportsSchedule`, `LiveSports` | Bounded schedule requests, parsing, saved snapshots, date selection, and game-to-guide matching. |
| `SportsGuide`, `SportsStreams` | Team recognition, coverage evidence, stream ranking, and automatic-choice eligibility. |
| `SportsStreamCache`, `SportsBrowseWork` | Session coverage reuse and bounded work for the focused/visible games. |
| `SportsReplays`, `SportsReplayDiscovery` | Historical guide matching and metadata-only provider archive discovery. |
| `SportsScoreDelay`, `GameReminders` | Display history and app-local reminder state. |
| `MainActivity`, `SportsPanel`, `SportsCalendarLoader`, `SportsScoreStrip` | Lifecycle, worker ownership, remote focus, presentation, and the handoff to the existing player. |

`LiveEvent` carries league/event identity, exactly two recognized teams, start
time, state, advertised networks, scores, records, logos, status, and supported
game situation details. Event keys include the league: IDs from separate
competitions must not collide. `LiveSportsSnapshot` records events, fetch time,
and leagues with successful scoreboard responses. `SportsGame` adds separate
collections for EPG broadcasts, exact channel-title matches, and unconfirmed
candidates; these collections must not be treated as interchangeable evidence.

States distinguish upcoming (`pre`), live (`in`), completed (`post`), and held or
uncertain (`hold`) events. A future or excessively old scoreboard item must not
be promoted to live. Unknown teams or malformed two-team competitions are
skipped rather than guessed.

## Schedule loading and failure behavior

Schedule requests use the public ESPN HTTPS scoreboard/team endpoints. They send
league, date, and team selection, not playlist credentials or channel lists.
The default schedule includes yesterday to retain games crossing midnight;
calendar requests translate the selected local day to the necessary Eastern
dates. Only enabled leagues are requested.

Requests use four workers, a four-second per-request timeout, and response limits
of 2 MiB for professional leagues or 6 MiB for college leagues. A normal schedule
load has a twelve-second aggregate budget; a calendar load has six seconds.
Parsing allows at most 500 events per response and eight competitions per event.
These limits are metadata budgets, not playback connection allowances.

Partial replies publish progressively without erasing pending leagues. A
completed refresh replaces returned leagues while retaining unavailable leagues;
retained live events become uncertain when a nonempty completed refresh cannot
confirm them. An entirely failed refresh preserves the previous snapshot.
Snapshots older than two minutes are not fresh, and the UI labels saved results
when the schedule is unavailable. Calendar requests debounce by 350 ms, reject
obsolete callbacks, retain up to eight in-memory date/league snapshots, and
refresh today's open calendar every minute.

Schedule persistence uses encrypted files through `SecureStore`. In-memory
merged schedules retain up to 2,048 events; serialized schedule snapshots retain
up to 1,024. A saved schedule is useful for browsing but cannot establish that a
provider stream works.

## Coverage, quality, and bounded discovery

The schedule remains browsable before playlist matching finishes. Matching uses
the shared guide index where available, with a cancellable local pass and
separate bounded provider EPG lookups. Current EPG must identify the requested
teams in the appropriate time window. An exact live channel title can supply
coverage without EPG, but replay/highlight titles, wrong dates, generic network
names, and positively conflicting current listings cannot confirm the game.

When actual matchup feeds are known, normal alternatives show those feeds.
Generic broadcaster suggestions remain explicitly unconfirmed fallback choices
only when no actual matchup feed is known. Automatic game selection requires
current confirmed coverage; manual stream choice remains available. Audio-only
and VOD entries are excluded. Sports preferences must not hide unrelated Guide
channels or interrupt current playback.

Resolution ranks before frame rate. FPS preferences break ties at the same
resolution; unknown FPS and lower-quality streams remain playable. Provider
quality labels and quality observed during actual playback remain distinct
evidence. Recent playback failures can demote alternatives without proving the
underlying matchup is absent. No background player probes are permitted.

Browse warming prioritizes the focused game, visible games, and up to two
followed-team games, with at most eight target games by default. Provider coverage
confirmation selects at most twelve channel targets within a 3.5-second budget
and caps each request at the remaining budget or one second. An already confirmed
feed can trigger a bounded higher-resolution EPG check; a generic UHD name alone
cannot displace confirmed HD coverage.

`SportsStreamCache` holds up to 128 session entries and no players. Context
includes source, generation, lineup, guide evidence, and favorites. Changed
ownership, game identity, relevant listings, or programme boundaries invalidate
cached evidence. Confirmed coverage can survive unrelated guide refreshes and
the ordinary two-minute retry timer; unresolved searches retry. Incomplete
matching must not become an automatic-play result, and an older worker must not
erase coverage already found by newer work.

## Scores, spoilers, and reminders

Score delay is a presentation setting from 0 to 300 seconds, with an optional
override for the playing channel. Cards, hero details, calendar, and the score
overlay use the same presentation path. Scores, clock/status, records, situation,
possession, and final state advance together from observed history. A cold or
insufficient history displays a waiting state rather than leaking current
scores. Delay does not change schedule matching, reminders, or video timing;
upcoming events and finals more than twelve hours after their start bypass it.
History is bounded to 1,024 games and twelve frames per game and clears if its
monotonic clock moves backward. Completed-score hiding is a separate presentation
preference.

Reminders are encrypted, app-local state for at most 64 upcoming events. They
use league/event identity, accept schedule corrections, avoid known postponed,
canceled, suspended, or delayed starts, and remember delivery to prevent repeats.
Completed reminders are removed; stale saved reminders are pruned after 48 hours.
They are foreground playback banners, not OS alarms or background notifications.
The banner waits for unobstructed playback, preserves playback, and restores
focus when dismissed. Scoreboard delay must not defer a reminder's actual start.

The optional score overlay shares schedule data and score presentation. Opening
it pins it until closed; explicit remote focus enables browsing. Tuning a live
game uses confirmed alternatives on the existing player, and leaving the overlay
cancels pending selection work. Final games route back to their league's Sports
details.

## Replays and recovery

A replay requires a completed game, a supported channel archive window, and a
historical programme matching both teams near the event start. Archive evidence
belongs to the exact provider channel, even when two variants share an XMLTV
identifier. A previous live match is a search hint, not proof that an archive
exists. Quality ranks replay choices but does not exclude an otherwise available
archive; playback rechecks expiration and the owning source's HTTP consent.

Background replay discovery prioritizes the focused completed game and receives
the bounded browse target set. It reuses saved guide/history before requesting
metadata, runs two provider requests concurrently, and checks up to six channels
per batch within 3.5 seconds. Inventory search progresses across batches rather
than declaring later variants unavailable after the first shortlist. Histories
are cached for ten minutes, capped at 128 channel entries and 16,000 programmes.
Failures and timeouts remain incomplete and retry; they are not a confirmed
empty archive. Source, generation, or lineup replacement clears ownership, and
pause/close cancels work and rejects stale callbacks.

Automatic initial playback plans try at most three distinct eligible feeds.
Optional sustained-buffer recovery operates only on an actively playing live
game, with no on-demand playback or multiview session. It reuses the primary
player and chooses another confirmed, allowed feed. The configurable sustained
buffering threshold is bounded to 10–30 seconds; recovery allows at most three
switches per ten-minute window, with at least thirty seconds between switches,
and does not cycle through already tried feeds in that window.

## Verification and known limits

The JVM suites covering these contracts include `LiveSportsTest`,
`SportsCoverageTest`, `SportsResultsTest`, `SportsQualityPreferenceTest`,
`SportsStreamCacheTest`, `SportsBrowseWorkTest`, `SportsCalendarTest`,
`SportsScoreDelayTest`, `GameRemindersTest`, `SportsReplaysTest`,
`SportsReplayDiscoveryTest`, and `SportsBufferRecoveryTest`.

Isolated TV scenarios in `SpareDeviceTest` cover first-visit league selection,
large-slate focus and responsiveness, score delay across all surfaces, reminders,
multi-provider matching, cached game playback, replay return navigation,
RedZone, the pinned overlay, and same-player buffering recovery. Follow
[Testing](../TESTING.md) for required checks and fixture isolation.

Public scoreboard availability, team aliases, and provider EPG/archive accuracy
remain external limitations. Coverage means metadata evidence, not a successful
video connection, subscription entitlement, or guaranteed picture quality.
Synthetic tests do not validate real provider availability or hardware capacity.

## Change notes

- 2026-11-10: Recorded the existing Android Sports contracts from source and
  regression tests; no Sports behavior changed.
