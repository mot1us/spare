# Performance and navigation audit — 0.14.2

Reviewed native guide navigation, playback state, source switching/removal, Sports artwork/focus, and Library metadata/catalog navigation.

## Fixed

- Sports delayed focus retries could reclaim focus after newer input or target changed stream results. Requests now validate input, visibility and stable item identity.
- Focused-game artwork competed with speculative requests. It now runs first; obsolete queued work is discarded and artwork workers run at background priority.
- Artwork bindings skipped while paused, throttled or busy could remain blank forever. A later binding can retry; completed images remain bound after memory-cache eviction.
- Hidden Sports loading completion could leave placeholders visible on return.
- Library metadata refresh rebuilt buttons and moved focus away from Back. It preserves the selected action and rejects stale backdrop callbacks.
- M3U movie Back incorrectly entered Xtream loading; episode Refresh reused cached episodes without fetching new data. Both paths now behave as labelled.
- RecyclerView repeatedly hashed and formatted channel/category IDs during layouts. Numeric IDs are cached, with the same identities; one redundant full-channel scan is removed.
- Small quality-observation changes queued whole-map persistence up to twice per second. Changes are batched every five seconds and flushed on source switch/backgrounding. Current quality and filtering still update immediately.
- Guide labels could lag behind programme rows at a time boundary. Both now refresh together.
- Removing the last playlist left VOD/resume state behind. It now clears player media, source-owned callbacks and browsing state. Switching sources also discards stale deferred refresh data.

## Suggested next features — not implemented

1. Quality discovery: a user-started, cancellable check of a selected category or favorites, with progress and verified/unknown totals. Respect the provider connection limit and foreground playback. Avoid automatically probing an entire playlist.
2. Remember the last focused channel for each category, so returning to a large category restores your place.
3. Remove from Continue watching / mark watched, available from a title’s details.
4. Show the age of cached Sports stream matches, with a direct retry when coverage changes.

Validation uses synthetic sources in the isolated TV emulator plus the project release checks. Real provider buffering, HDMI frame-rate switching and four-stream decoder capacity still need the onn hardware check. No new provider connections, services or metadata dependencies were added.
