# Project documentation

Start with [the app README](../README.md) for features, installation, and remote
controls, then [architecture](specs/architecture.md) for ownership and platform
boundaries. These documents describe the current intended behavior; dated audit
reports and historical test notes describe the version they reviewed.

## Domain contracts

| Document | Responsibility |
| --- | --- |
| [Architecture](specs/architecture.md) | Components, data ownership, lifecycle, platform scope, and trust boundaries. |
| [Provider ingestion](specs/provider-ingestion.md) | M3U/Xtream input, HTTP consent, EPG, cached startup, and refresh scheduling. |
| [Android provider storage](specs/android-provider-storage.md) | Encrypted persistence, account identity, invalidation, and late-writer isolation. |
| [Guide and navigation](specs/guide-and-navigation.md) | Remote focus, categories, Local, programme time, appearance, and voice fallback. |
| [Playback](specs/playback.md) | Primary/multiview player limits, transport ownership, and missing-media recovery. |
| [Library and Search](specs/library-and-search.md) | Lazy catalogs, movies/episodes, catch-up, history, Watchlist, and shared search. |
| [Sports](specs/sports.md) | Schedules, stream evidence/ranking, replays, score delay, reminders, and recovery. |
| [Updates and release](specs/updates-and-release.md) | Android update trust, installation, versioning, and publication gates. |
| [macOS preview](specs/macos.md) | Native Mac scope, source/task ownership, secure caches, VLC, and known gaps. |

## Development and operation

- [Testing](TESTING.md): build prerequisites, required checks, isolated device
  suites, and the physical onn/TV checklist.
- [Releasing](RELEASING.md): versioned delivery, signed publication, and updater
  configuration; [Mac setup](../macos/README.md): packaging and native controls.
- [Security](../SECURITY.md): input, transport, storage, and disclosure boundaries.
- [Buffering](BUFFERING.md): player budgets, diagnostics, and recovery behavior.
- [Local channels](LOCAL-CHANNELS.md): offline reference data and matching limits.
- [Changelog](../CHANGELOG.md) and [third-party notices](../THIRD_PARTY_NOTICES.md):
  delivered changes and attribution.

## Reviews and durable working notes

[The 0.32.16 review](AUDIT-0.32.16.md) records its fixes, verification, and remaining
findings. [0.18.0](AUDIT-0.18.0.md) and [0.14.2](audit-0.14.2.md) are historical
reviews, not current acceptance specifications.

[Project map](../.agents/notes/PROJECT_MAP.md) records entry points and commands;
[lessons](../.agents/notes/LESSONS_LEARNED.md) preserve durable implementation
pitfalls. Follow [AGENTS.md](../AGENTS.md) when changing the project. Update the
affected domain spec with behavior changes, reuse existing operator guides, and
record unresolved limitations explicitly instead of converting them into promises.
