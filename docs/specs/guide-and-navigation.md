# Android guide, navigation, and appearance

## Scope and interfaces

`MainActivity` coordinates the guide and global Search, Guide, Sports, Library,
and Settings dock. `GuideAdapter`, `SidebarAdapter`, `NavigationRail`, and
`TvFocus` own remote focus and list presentation. `GuideTimeline` owns per-channel
programme browsing. `GuideTheme`, `ThemeEditor`, and `AppFont` own appearance.
This contract applies to Android TV; [macOS](macos.md) has separate controls.

## Guide data and selection

Enabled playlists contribute source-scoped channels and categories. Provider
headers expand/collapse on OK; highlighting a header never tunes a channel.
The optional category-on-highlight setting changes the displayed list after
focus settles. Selecting another category clears the guide query and restores a
valid row. Provider category order is retained, with the optional stable USA-first
ordering applied as a display preference.

The live guide excludes media/VOD entries and temporarily unavailable channels.
It intersects playlist/category scope, category visibility, Favorites, Local, and
the active channel-name query. Current guide filtering does not apply the old
minimum-resolution/FPS exclusion UI described by historical test notes. The
`GuideFilters.matches` helper remains available to domain-specific callers;
quality preferences and Sports ranking must not be inferred from its tests alone.

Programme lookup uses the channel's guide key and `[start, end)` intervals.
No EPG, a failed short-EPG request, or a saved-guide load in progress produces an
explicit guide message while leaving the channel available. Guide import and
refresh ownership are defined in [Provider ingestion](provider-ingestion.md).

Each channel has its own future time window, in 30-minute slots, bounded to
48 hours ahead and never before the current live slot. Left, Back, or Now can
return that row to live without moving other rows. Old windows expire as live
time catches up; at most 5,000 row windows are retained. Time labels follow the
device timezone/locale and the saved 12/24-hour preference.

## Remote and player invariants

- Focus movement does not tune. Guide OK selects the channel and normally opens
  full screen; the preview-first preference allows an initial tune in the guide.
  Selecting the already playing channel expands it without retuning.
- Hold OK toggles Favorites. Short Back closes the current overlay or moves from
  guide rows to categories, then the main dock. When a row is ahead in time, Back
  first resets its time. Hold Back returns to full screen when available.
- Full-screen live Up/Down changes channel, Left recalls a successfully watched
  channel, and OK opens playback details. Open panels, Sports overlay focus, and
  on-demand controls take precedence over these live shortcuts.
- The hidden dock opens through deliberate navigation and must not steal focus
  during automatic Android focus restoration. Right returns to section content.
- Remote repeats keep the focused row visible. Guide looping is configurable;
  deferred focus restoration must not override a newer user navigation action.
- Guide/preview layout, Settings, theme changes, and programme refresh must not
  recreate the primary player or its continuing playback attempt. Backgrounding
  follows the separate [playback lifecycle](architecture.md#lifecycle-and-asynchronous-work).

Search text/voice and on-demand Back semantics are specified in
[Library and Search](library-and-search.md); Sports rail/calendar focus and
multiview selection are specified in [Sports](sports.md).

## Local channels

Local combines up to eight saved U.S. city/state entries using offline reference
data and explicit provider location labels. Entries are trimmed to 80 characters,
deduplicated without case, and serialized as a bounded semicolon-separated value.
Draft typing is separate from saving: Add region, Save changes, keyboard
Done/Enter, or selecting a suggestion commits the entry. Removing a region saves
the change immediately and recomputes matches in the background.

Only existing playlist channels can match; empty or unknown locations must not
expose the entire lineup. Quality variants remain available, and category
visibility still applies. Match caches bind to location and station/channel
identity; malformed or obsolete entries fall back to matching. A loading or empty
Local state is distinguishable from a successful match. See
[Local channels](../LOCAL-CHANNELS.md) for reference-data provenance and limits.

## Appearance and input failures

Theme edits persist immediately and preview the guide without retuning.
Custom dark-background tints and contrasting focused text retain readable
selection; density, spacing, text size, opacity, border, and radius settings are
bounded. Fonts ship offline. Appearance does not change provider identity,
credentials, playback ownership, or transport consent.

Voice input uses Android's configured recognition service inline, with runtime
microphone permission and a bounded session. Denial, timeout, unavailable service,
or cancellation leaves typing usable. Leaving Search or stopping the activity
stops the recognizer and restores the listening-related playback volume state.
Spare does not own the remote's OS-level microphone button or guarantee offline
speech recognition.

## Verification and limits

JVM coverage includes `GuideClockTest`, `GuideChannelIndexTest`,
`GuideProgrammeIndexTest`, `CategoryOrderTest`, `LocalChannelsTest`,
`LocalMatchCacheTest`, and `TvFocusTest`. Device scenarios exercise actual D-pad
focus, held keys, preview/full-screen continuity, category changes, theme edits,
Local regions, and controlled speech-service success/failure. Physical remote
microphone and display behavior still require [the onn checklist](../TESTING.md).

## Change notes

- 2026-11-10: Captured current guide/navigation contracts and distinguished them
  from superseded quality-filter and Local auto-save test instructions.
