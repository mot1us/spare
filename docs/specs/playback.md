# Playback and missing-media contract

## Scope

Android uses one primary Media3 player for live TV, catch-up, movies, and episodes.
Only an explicitly opened multiview session may add players, up to four total.
Exactly one pane is audible; leaving multiview or pausing the activity releases
all additional players. Guide, settings, and preview/full-screen navigation
preserve the primary player and current media attempt where playback continues.

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

## Verification and limits

`PlaybackRecoveryTest` covers missing-resource classification and bounded
recovery. The isolated TV suite exercises real synthetic HLS child failures and
missing VOD endpoints through the activity, as well as player continuity,
background shutdown, and multiview. Synthetic tests do not establish actual
provider availability, hardware decoding capacity, or HDMI refresh behavior.

## Change notes

- 2026-11-10: Documented the shared missing-endpoint rule and corrected VOD
  catalog suppression after a child-resource failure.
