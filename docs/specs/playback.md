# Android playback

## Scope

Android uses one primary Media3 player for live TV, catch-up, movies, and episodes.
Only an explicitly opened multiview session may add players, up to four total.
Exactly one pane is audible; leaving multiview or pausing the activity releases
all additional players. Guide, settings, and preview/full-screen navigation
preserve the primary player and current media attempt where playback continues.

## Ownership and lifecycle

`MainActivity` owns the primary `ExoPlayer`, its attached `PlayerView`, playback
attempt tokens, and media transitions. Guide previews resize that view rather
than create another player. A channel change or replay/VOD selection begins a
new attempt; asynchronous frame, timeout, and recovery callbacks must still
belong to the current attempt before changing UI state or saved quality.
See [Library and search](library-and-search.md) for on-demand resume and return
destinations.

Pausing the activity pauses the primary player before cache or image cleanup and
releases multiview extras. Stopping the activity stops primary playback, cancels
recovery/monitoring work, and clears display timing requests; destruction releases
the player. Audio must not continue over the launcher or another app. A transient
pause/resume preserves whether playback was running rather than unconditionally
starting paused content.

## Requests and failures

Every media request must obey its owning playlist's HTTP consent. The updater
always requires HTTPS. Redirects and media child resources do not acquire another
playlist's consent. Playback requests must not expose provider credentials in
diagnostics, tests, or checked-in artifacts.

HTTP 404 or 410 only proves that the requested resource is missing. A channel or
VOD title can be hidden only when the failed request identifies its own media
endpoint, including the query string. A missing segment, variant manifest,
encryption key, or subtitle is not evidence that its parent title is unavailable.
URI fragments do not distinguish HTTP resources. Unparseable or missing failure
addresses cannot establish that a title is missing.

Failed live startup may try the configured alternate format. Only confirmed
missing endpoints across all startup attempts temporarily hide the channel.
Previously playing live streams retain bounded reconnect behavior. Movies and
episodes whose own endpoint is missing are excluded from Library matches for
one hour; a failed child resource leaves the catalog entry available to retry.
Authentication, rate limiting, and generic network failures do not hide titles.

## Buffering, recovery, and diagnostics

`PlaybackLoadControl` and `ProviderLoadErrors` own loading/retry policy;
`PlaybackRecovery`, `PlaybackWarmup`, and the activity monitor bounded startup
and progress. [Buffering and recovery](../BUFFERING.md) records the current time,
memory, and reconnect budgets. Loading targets are not guarantees about provider
delivery or whole-app memory usage. Additional players have smaller byte budgets
than the primary player. Playback stays at normal speed, and HLS live offset
comes from provider playlist timing.

Transient loads should preserve buffered media and the current player when
possible. Sustained lack of progress can reconnect within the bounded policy;
account failures and rate limits remain visible. Exhausted attempts expose a
retry/alternative action rather than an endless reconnect loop. Optional
[Sports recovery](sports.md) chooses another confirmed feed using the primary
player and has its own bounded switch policy.

Playback details distinguish startup/loading, buffered seconds, channel-session
stall/load/reconnect counters, dropped frames, source timing evidence, requested
frame rate, and observed display refresh. These are local diagnostics; provider
addresses and credentials must not appear in them. Metadata/cache warming must
not open additional media connections, and expensive background work yields while
playback is opening or buffering.

## Audio and captions

Audio controls list supported tracks from the current player and permit an
explicit override or Automatic selection. Audio track selection is current
player state, not a persisted per-channel audio-language contract.

Subtitles have an app-wide Off/Automatic default and a saved per-channel override
for live channels. A channel can choose the default, Off, Automatic, or a supported
track identified by MIME type, language, service number, and label. On-demand
subtitle choice is separate session state and shares the app's size/style
settings. An app default change does not overwrite explicit channel choices.

Provider-declared caption descriptors take precedence over compatibility readers.
Undeclared embedded CEA-608/708 services can be tried through the existing stream;
Automatic rotates only guessed compatibility services, stops when cues arrive,
and uses a finite scan. It must not cycle declared languages or open a probe
connection. A track listing does not guarantee that the current programme emits
captions. Enabling previously disabled HLS captions may reprepare the same
player to expose embedded services; on-demand progress is saved first.
`SubtitleAppearance` applies text styling and bounded image-caption scaling.

## Quality evidence and display timing

Provider labels and observations from actual playback remain distinct quality
evidence. `SourceVideoFrames`, `FrameCadence`, `AvcHeader`, and `VideoRate` combine
input media timestamps, codec headers, declared format, decoder metadata, and
rendered cadence. Render callbacks can omit frames and are not proof of source
rate or HDMI refresh. Confirmed interlacing distinguishes encoded frame rate
from motion/field rate; a provider label alone must not invent that conversion.
Saved observations must belong to the channel and current playback attempt.

Frame-rate matching is optional and requests display timing without replacing the
player or opening another stream. Cached live-channel timing is an early hint;
fresh stable source evidence can replace it. Ordinary changes must settle, and
small integer/fractional corrections need the longer stability window so jitter
cannot repeatedly switch display modes. VOD starts without inheriting the prior
live channel's timing. Disabling matching, entering multiview, or stopping clears
the display request. Actual mode selection, blackouts, and supported rates remain
device/TV behavior, not guarantees established by the requested rate.

## Multiview

`Multiview` owns up to three additional players only while the user has explicitly
opened a two-up or four-up session. Re-adding the same channel does not add a
connection. Each extra uses its own source's HTTP consent and known provider
connection limit; disabled sources and known exhausted limits reject additions.
An unknown limit is not evidence that the provider permits more streams.

Focusing a populated pane selects its audio and mutes the others. Selecting the
primary pane for full screen retains primary playback. Selecting an extra pane
closes extras and tunes that channel on the primary player. Browsing to add a
game/channel keeps existing decoder surfaces attached; it does not silently
create another session. Switching layout does not recreate existing players.

A failed extra pane shows a local error without closing working panes. Removing
a pane or its source releases its player. Closing the session, choosing full
screen, or pausing/backgrounding releases all additional players and restores the
primary view/volume. Extra players do not independently request display-rate
changes. Decoder capacity and real provider connection allowances require
physical validation; four layout positions do not promise four decodable feeds.

## Verification and limits

`PlaybackRecoveryTest`, `ProviderLoadErrorsTest`, `PlaybackWarmupTest`, and
`PlaybackBufferBudgetTest` cover recovery classification, retry policy, startup
work, and memory budgets. `QualityEvidenceTest`, `SourceVideoFramesTest`,
`FrameCadenceTest`, and `FrameRateTargetTest` cover source timing and stable
display targets; `SubtitleAppearanceTest` covers caption presentation.

The isolated TV suite exercises real synthetic HLS child failures and missing
VOD endpoints through the activity, player continuity, background shutdown,
captions, and multiview. Follow [Testing](../TESTING.md), including its physical
onn/TV checklist for provider, decoding-capacity, and HDMI refresh claims.
Synthetic checks do not establish those hardware/provider properties.

## Change notes

- 2026-11-10: Documented the shared missing-endpoint rule and corrected VOD
  catalog suppression after a child-resource failure.
- 2026-11-10: Recorded existing lifecycle, buffering, tracks, quality/display
  timing, and multiview contracts without changing playback behavior.
