# Buffering and recovery

In 0.19.1, transient loads use Media3's default retry budgets (three normally, six for progressive live), rather than a one-retry override. Live HTTP permits twenty seconds between reads. Authentication, rate-limit and definitive missing-endpoint errors remain non-retryable; application reconnects retain their separate bounded budget.

HLS derives its live offset from the provider playlist (including hold-back and segment duration), rather than a fixed twelve-second override. Playback stays at 1×. The loading window below is a target, not media that can be manufactured beyond the provider's live edge. Decoder metadata is cached per format and quality/display updates run at most twice per second; frame-progress tracking remains per frame.

Press OK during live playback to see buffered seconds and per-channel stall, load-error and reconnect counts. These counters are local, reset on channel selection, and contain no addresses or credentials. A reconnect can skip unavailable live content; a transient load retry should preserve the existing player connection and buffered media whenever the transport supports it.

Spare uses the existing Media3 player connection to buffer playback. The primary player has a configured 60–90 second loading window, a 1.5 second live startup threshold and a 10 second live rebuffer threshold. Movies and episodes use a five-second startup cushion and twelve-second rebuffer cushion, while fully loaded short files and the byte budget can allow earlier playback. These are load-control settings, not a promise that the provider always delivers that much data. The primary byte target is one quarter of the app heap, bounded to 32–96 MiB; each additional multiview player uses one sixteenth, bounded to 8–24 MiB. These targets are not whole-app memory caps.

Brief stalls can resume on the same connection. Sustained lack of progress is monitored per playback attempt, with a 20 second READY observation window. At 45 seconds without position progress, a buffer that has grown within the last ten seconds may continue filling; 90 seconds without position progress is the absolute ceiling. Reconnects stay bounded to two within 120 seconds; account and rate-limit failures remain visible.

In 0.18.2, a played live HLS stream can use that existing reconnect path after a child segment or variant returns 404/410. A missing channel endpoint, authentication error or VOD request does not take this path. Buffer sizes and reconnect limits are unchanged.

Only a missing media endpoint can hide a channel or VOD title. A missing HLS child manifest, segment, encryption key, or subtitle leaves its parent title available to retry. See the [playback contract](specs/playback.md).

IPTVnator's embedded MPV implementation uses up to six reconnect attempts, with exponential delays from two to thirty seconds and a reset after thirty seconds of stable playback. Its loading-state tracker offers recovery after thirty seconds. These are different recovery policies and engines; code inspection cannot establish which plays a particular provider more smoothly on an onn box.

References: [MPV reconnect coordinator](https://github.com/4gray/iptvnator/blob/7a390f4bba002f1ba0070de16ddb4498ca35e14d/apps/electron-backend/src/app/services/embedded-mpv-reconnect.ts), [loading-state tracker](https://github.com/4gray/iptvnator/blob/7a390f4bba002f1ba0070de16ddb4498ca35e14d/libs/ui/playback/src/lib/embedded-mpv-player/embedded-mpv-stalled-tracker.ts).
