# Lessons learned

## 2026-11-10 — Provider identity must gate cache transitions

What went wrong: Publishing an edited account identity before asynchronous file cleanup made old source-scoped data temporarily readable as the replacement account; deleting identity state let late writes recreate removed core caches. Mac caches also lacked a source identity in their encrypted payloads.

Why it happened: File names identified a saved source record rather than the provider account, while task cancellation and cache deletion completed separately from UI state changes.

Rule: Android must finish pending provider invalidation before derived reads/writes, reject obsolete invalidators, and preserve deletion tombstones. Mac cached payloads must verify their source identity before restore. Cancel producer publication before deleting its files, and guard every asynchronous UI result with ownership.

Status: active

## 2026-11-10 — Missing media children do not invalidate catalog titles

What went wrong: A VOD segment 404 removed its parent title from Library for one hour.

Why it happened: Playback error handling classified the HTTP status without comparing the failed request to the title's media endpoint.

Rule: Use the shared endpoint-missing predicate for live/VOD suppression. A missing segment, variant, key, or subtitle is not evidence that the title itself is absent; retain an end-to-end HLS regression.

Status: active
