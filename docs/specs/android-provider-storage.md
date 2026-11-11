# Android provider storage

## Responsibility and interfaces

`SecureStore` owns encrypted playlists, provider URLs, lineups, guide data,
catalogs and viewing history. `bindProvider` selects the account identity for a
saved source ID. When it returns `true`, the caller must finish
`invalidateProviderFiles` on its background worker before restoring or saving
derived data for that source. `removeCache` removes the source's cached data.

Provider identity includes the source kind, address and account credentials.
Renaming a source or changing HTTP consent does not change its identity. M3U
paths retain significant trailing slashes. Display preferences remain scoped to
the saved source ID; progress and Library favorites also include account identity
so editing an account does not transfer viewing history to the replacement.

## Data and security invariants

- Sensitive files use Android Keystore AES-GCM and atomic replacement. Credentials
  and provider URLs must never enter plaintext preferences, diagnostics or logs.
- Reads authenticate ciphertext before parsing. Encrypted file reads are bounded
  to 128 MiB. Importers and catalog readers retain their separate item, field and
  decompressed response limits.
- A changed account immediately makes source-scoped derived caches unavailable.
  Publishing the replacement identity must not make the previous account's
  lineup, guide URL, EPG or legacy catalog readable under the new credentials.
- Cleanup remains off the UI thread. A persistent pending flag blocks derived
  reads and writes until cleanup succeeds. Rebinding after process recreation
  returns `true` while cleanup remains pending.
- Invalidation checks both current identity and pending state. An obsolete or
  repeated invalidation cannot delete a newly populated cache. Cache reads,
  mutations and removal coordinate through the shared cache lock; parsing a
  decrypted snapshot happens outside that lock.
- Removing a source retains an empty identity tombstone. Late derived-cache or
  quality writes cannot recreate the removed source's data. Explicitly rebinding
  the same source ID starts a fresh invalidation before reuse.
- Account-scoped viewing history remains readable for its original account after
  an account edit. Source removal deletes history for every account under that ID.
- Source removal invalidates Library imports before queueing file deletion.
  Superseded producers cannot append pages, publish partial snapshots, replace a
  catalog index or repopulate search results after invalidation.
- Background refreshes coalesce only when the complete source snapshot matches.
  An edited source may share a saved ID with an outstanding request, but must not
  receive that request's retained guide or error state as its own result.

## Failure modes and validation

Authentication failures and malformed caches are failures, never plaintext
fallbacks. Callers may retain usable in-memory data or reimport from the provider.
A cleanup failure keeps caches unavailable and is retried on a later bind.
Cleanup verifies that AtomicFile base, pending-write and backup paths were
removed; a failed directory listing also leaves invalidation pending. Orphaned
AtomicFile sidecars are included when pruning provider caches.

The isolated `encryptedStorageAuthenticatesDataAndNeverStoresPlaintext` device
scenario covers authenticated ciphertext, concurrent storage instances, the
account-edit cleanup window, interrupted cleanup, stale invalidators and late
derived writes after deletion. The viewing-history scenario separately verifies
account scoping and retention across edits. The source-dialog scenario removes a
source with an outstanding catalog writer and rejects its later publication.
`editedSourceRefreshDoesNotJoinThePreviousAccount` holds an old provider response
while changing the address, then verifies the replacement fetches and receives
only its own channels.

## Change notes

- 2026-11-10: Closed the gap between publishing an edited account identity and
  asynchronously deleting the old account's caches; guarded repeated cleanup and
  late derived writes after deletion.
