# 0.18.0 audit

| Area | Finding and change |
| --- | --- |
| Quality | Some Sports/RedZone paths still treated quality preferences as rejection filters. All matched quality variants now remain playable. Ordering uses coverage, trustworthy quality evidence and preferences; labels distinguish provider declarations from stream/decoder/timestamp evidence. |
| Provider metadata | Channel-name interlace labels could be doubled and missing widths guessed as 16:9. Keep explicit declarations, convert only explicit encoded frame rate plus field order, and avoid borrowing provider FPS after a contradictory observed format. |
| Catch-up | Live ranking cannot establish archive availability. Independently consider archive-capable variants; use saved historical EPG first, then a bounded metadata-only background check with per-channel caching. Missing/failed metadata is distinct from a confirmed replay. |
| Buffer recovery | A recovered buffer could inherit old READY-frame age. Require a complete READY observation window while retaining the absolute no-progress bound. Restore the initial-frame timeout if READY is reached during a pause. |
| UI work | Cache guide font faces per appearance update, preserve row holders while changing density, avoid repeated poster subscriptions, and coalesce theme application. |
| Cleanup | Remove duplicate stock track dialogs, redundant Sports Refresh and Library Live TV controls, unused constructor callbacks, and their obsolete call paths. Keep transports/dependencies that are actually used. |

Spare still uses one primary Media3 ExoPlayer. Additional players exist only in explicit multiview. Archive discovery opens provider metadata, never video streams, and pauses during buffering/multiview or outside Sports.

The existing primary 60–90 second load-control window, 1.5-second startup threshold, 10-second rebuffer threshold and time-prioritized 96 MiB target are preserved. The byte target is not a hard memory cap. Automatic recovery remains bounded to two reconnects in 120 seconds; the watchdog retains its 20-second READY observation and 45-second absolute position-stall ceiling.

Synthetic checks cover ordering, cached metadata migration, archive variants/cancellation, settings focus and player identity. Actual provider formats, HDMI matching and sustained onn multiview performance still need device feedback; an advertised FPS value does not prove provider motion quality.
