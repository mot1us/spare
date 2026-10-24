# Device testing

Automated local checks cover importer behavior, hostile data, the optimized APK, lint, encrypted storage, and a private Android TV emulator. The emulator uses a local fixture server and the AndroidX Media3 sample video; no real source credentials or personal accounts are required.

## 2026 onn 4K Pro + Samsung checklist

1. Install the signed initial prerelease APK. Record the onn firmware/Android version, Samsung model, and whether HDMI goes directly to the TV or through an AVR/soundbar.
2. Add a source using the TV keyboard. Verify M3U and Xtream separately if both are available. Check Google TV phone keyboard entry without sharing credentials in chat.
3. Verify guide import, logos, groups, channel-name search, Favorites, and audio/subtitle selection. For a missing guide, confirm channels still work and the app says no programme information.
4. While watching, Up/Down changes channels. OK opens the guide with the current channel still playing in the small preview. Moving focus does not retune. OK tunes and returns to full screen. Long-press OK toggles a favorite; Back returns from the guide.
5. Close/reopen and switch sources; check last-channel resume and separate Favorites. Disable internet temporarily and confirm the cached guide remains usable and playback errors offer Retry/Guide.
6. Test known 23.976/24, 25/50, 29.97/30, and 59.94/60 sources where available. Use the TV signal-information display to observe output refresh rates. Record unsupported modes and blackout duration. A source’s stated FPS alone does not prove HDMI matching.
7. Measure cold launch-to-usable UI, cached guide navigation, channel-selection-to-first-frame, and rebuffering separately. Record display-switch blackout separately from stream startup. Favor stable playback over an artificially low buffer.
8. Test a real signed update to a higher version code: quiet badge, explicit download, unknown-source permission, Android installation confirmation, data retention, and last-channel resume. Wrong signing key/package/hash and downgrades must be rejected.

Only promote the initial prerelease to stable after this physical checkpoint. Subsequent routine updates use one new tracked version per completed change, and preserve signing identity.
