# Buffering and recovery

Spare uses the existing Media3 player connection to buffer playback. The primary player has a configured 60–90 second loading window, a 1.5 second startup threshold and a 10 second rebuffer threshold. These are load-control settings, not a promise that the provider always delivers that much data. The 96 MiB byte target is not a hard memory cap.

Brief stalls can resume on the same connection. Sustained lack of progress is monitored per playback attempt, with a 20 second READY observation window and a 45 second absolute position-stall ceiling. Reconnects stay bounded to two within 120 seconds; account and rate-limit failures remain visible.

In 0.18.2, a played live HLS stream can use that existing reconnect path after a child segment or variant returns 404/410. A missing channel endpoint, authentication error or VOD request does not take this path. Buffer sizes and reconnect limits are unchanged.

IPTVnator's embedded MPV implementation uses up to six reconnect attempts, with exponential delays from two to thirty seconds and a reset after thirty seconds of stable playback. Its loading-state tracker offers recovery after thirty seconds. These are different recovery policies and engines; code inspection cannot establish which plays a particular provider more smoothly on an onn box.

References: [MPV reconnect coordinator](https://github.com/4gray/iptvnator/blob/7a390f4bba002f1ba0070de16ddb4498ca35e14d/apps/electron-backend/src/app/services/embedded-mpv-reconnect.ts), [loading-state tracker](https://github.com/4gray/iptvnator/blob/7a390f4bba002f1ba0070de16ddb4498ca35e14d/libs/ui/playback/src/lib/embedded-mpv-player/embedded-mpv-stalled-tracker.ts).
