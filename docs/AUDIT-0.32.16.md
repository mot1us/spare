# 0.32.16 project review

## Scope and approach

Reviewed Android startup, player lifecycle and recovery, HTTP transport, imports,
guide data, encrypted persistence, provider identity, catalog concurrency,
updater verification, and release tooling. Independently reviewed the complete
macOS Core, app-state, player, and native bridge sources. Findings were traced
through callers and existing regressions before changing behavior. No real
provider credentials, personal browser profiles, or personal devices were used.

The delivery integrates the reviewed changes with main through 0.32.15 and uses
version 0.32.16 / code 109 to preserve the existing version sequence.
Android integration review preserved the newer Sports tracing/index and Search
behavior alongside the provider-ownership fixes. The device run exposed one stale
Sports test expectation: upstream had changed the active prefetch key to a set,
but the test still compared it with a string. The assertion now checks membership
while retaining the worker gate and cancellation checks.

## Corrected findings

| Area | Defect and correction |
| --- | --- |
| Android VOD | A missing HLS child resource hid the entire title for one hour. Catalog suppression now requires a 404/410 for the media endpoint itself; children remain retryable. |
| Android source edits | New provider identity became visible before asynchronous cleanup, exposing old source-scoped caches. A persistent pending state blocks those reads/writes until current-owner cleanup completes; failed file deletion or directory listing keeps it blocked. |
| Android deletion | Dropping identity state made old writers appear unbound, and catalog producers could publish after deletion. Retained identity tombstones reject late core-cache writes; deletion cancels Library producers and publication checks generation before partial/final updates. |
| Android refresh | Requests for different account snapshots could share a source-ID-only refresh job. Refresh work and callbacks now distinguish the complete source snapshot. |
| Mac source lifecycle | Canceled EPG workers retained their slot; old filters/results could survive source switches or deletion. Work now has explicit generation ownership and old selectable state is cleared. |
| Mac cache identity | A failed account edit could restore the old account's cache after restart. Authenticated encrypted payloads now include source identity, excluding harmless display-name changes. Legacy unscoped caches require one provider refresh. |
| Mac XMLTV | ASCII-only scanning missed UTF-16/32 DTD declarations. Declaration rejection covers supported Unicode encodings before parsing. |
| Mac Xtream | An optional category endpoint failure blocked valid channels. Channels load with fallback grouping while required authentication/channel failures remain errors. |
| Mac recovery | Rebuffer timeout counted from original tune time, prematurely reconnecting working streams. Each buffering episode gets its own grace period; pause/resume resets observation and exhausted recovery stops VLC. |
| Mac Sports | Failed refresh dropped saved games, and the selected hero retained stale scores. Exact league/date cached games survive failed refresh; stale non-final games show Saved instead of Live, and selection resolves current visible data. |
| Release identity | An existing tag without a Release could attach new artifacts to old source. Publication now fails on existing tags or an unverified remote lookup. |
| Test isolation | Device scripts could target physical devices. All mutating device harnesses now require emulator serials. Swift `.build` output is ignored. |

Domain contracts live in [Android provider storage](specs/android-provider-storage.md),
[playback](specs/playback.md), and [macOS](specs/macos.md). The buffering guide was
updated to reflect existing heap-based budgets and the 90-second absolute stall
ceiling while queued data is still growing. Project-map and lesson notes record
commands and account-ownership rules for later work.

## Remaining findings

1. **Mac media transport consent:** Metadata requests validate redirects, but VLC
   owns media redirects and HLS child requests. The bridge does not enforce
   playlist HTTP consent on those internal requests. End-to-end enforcement needs
   a supported transport integration; initial URL validation does not solve it.
2. **Android M3U identity migration:** Stable IDs omit the URI port, so otherwise
   identical streams on different ports can collapse. A naive ID change resets
   favorites/history; this needs an explicit compatibility migration.
3. **Mac artwork bounds:** The current artwork cache has no total disk/decoded
   byte budget and may decode source-resolution images. Resource bounds and
   downsampling need a separate measured change.

Synthetic tests cannot establish real provider behavior, sustained physical
four-stream decoding, microphone behavior, or HDMI refresh matching. Those remain
covered by the physical onn/TV checklist. Dependency versions and locks were
preserved; this review did not claim a comprehensive upstream vulnerability audit.

## Verification

- Required Android Gradle tasks passed: unit tests, debug lint, debug APK,
  unsigned release APK, and instrumentation APK. All 335 JVM tests passed.
- Full isolated API 36 ARM64 Android TV suite ran 67 scenarios in 891.618
  seconds, with per-test progress and retained diagnostics. Sixty-six passed;
  the stale Sports prefetch assertion described above was the only failure.
  New failed-cleanup, edited-account refresh, catalog cancellation, HLS-child,
  and upstream 100,000-channel Search regressions passed. The instrumentation
  APK was rebuilt after correcting the assertion; the affected scenario passed
  alone in 20.164 seconds. Together, the full run and targeted rerun cover all 67
  scenarios successfully without repeating unrelated tests.
- Native self-update passed: package replacement, staged-file/session cleanup,
  and launcher reopening. Optimized-release playback smoke passed with a
  temporary test signing key. The test emulator was stopped afterward.
- macOS `swift test` passed all 19 tests. The universal build completed through
  `scripts/macos-build.py`; architecture and ad-hoc signature verification passed.
  Both native slices retain a macOS 13.0 deployment minimum.
- Native Mac smoke used an isolated demo data directory, in-memory credentials,
  and localhost media. Verified decoded 1920×1080/24 video, channel switching,
  pause/resume, preview/full-screen transitions, and Sports navigation.
  This functional smoke preceded the main-branch integration; macOS source did
  not change during integration, and the tests and universal package were rebuilt
  for the final version.
- Three release-safety tests, workflow YAML parsing, shell syntax, version script,
  and whitespace checks passed. Dependencies and lockfiles remain aligned.

The first Android invocation lacked an SDK location; verification completed with
a newly provisioned isolated SDK. The integration rerun encountered an old debug
signature in the disposable emulator before tests began; the synthetic app/test
packages were removed for a clean install. A sandboxed Swift invocation could not access
compiler caches; its approved rerun passed. The native UI tool stalled during
initial inspection, allowing the finite fixture to end; reconnect and subsequent
UI checks passed. Existing compiler/toolchain deprecation warnings remain.

Release publication is not part of this update. The release request retains its
previous version, so this delivery does not trigger publication.
