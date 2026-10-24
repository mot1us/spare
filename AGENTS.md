# Spare project instructions

Keep Spare a small native Android TV app: Kotlin, Android Views, one Media3 player. Preserve remote focus and playback continuity between preview and full screen. Do not add accounts, advertisements, analytics, a hosted credential service, VOD, recording, timeshift, or multiview without explicit scope approval.

For every completed requested project update, increment `version.properties` before the delivery commit. Default to a patch increment; use a minor for substantial compatible features and a major for breaking releases. Increment `versionCode` monotonically. Gradle derives native version values from this file. Keep dependency locks in sync, add a concise changelog entry, include the delivered version in the commit message, and report it. Intermediate work belongs to the same update. Do not rewrite published history/tags. Initial version is 0.1.0 / code 1.

Required checks: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`, version script, and isolated Android TV device tests for changes to startup, playback, storage, guide, focus, or remote behavior. Hardware refresh-rate/provider claims require the physical onn/TV checklist. Use synthetic fixtures; never commit real credentials or provider URLs.

Preserve release signing identity, the verified update channel, HTTPS-only updater, source HTTP consent, input bounds, XML entity/DTD rejection, and encrypted local storage. Signing keys, passwords, local SDK paths, and generated build output must never be committed. Release signing is only allowed from trusted main-branch jobs with protected secrets.

Use an isolated browser for any app browser testing; otherwise a dedicated profile without personal accounts. Ask before using the owner’s personal browser. Use an isolated Android emulator without personal accounts for device tests.

Never log into, SSH into, or administer the owner’s VPS. Prepare files and commands locally; the owner performs any VPS operations manually. Spare distribution uses GitHub and does not require a VPS.
