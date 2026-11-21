# Playback startup diagnostics and Best Match consistency

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Smart Streams' Best Match recommendation obey the same filters and provider selection as the visible list, and add bounded Android startup load-phase evidence without changing playback behavior.

**Architecture:** Keep the existing Smart Stream score and ordering unchanged; derive Play Best candidates from all sources after provider and Smart Streams filters, while preserving free-text search as a separate lower-list concern. Extend the existing Media3 1.8.0 AnalyticsListener only for first manifest/media loads and first completion per category, logging monotonic elapsed time, load duration, byte count and redacted URLs.

**Tech Stack:** Kotlin 2.4.10, AndroidX Media3 1.8.0, Kotlin test, existing InAppLogger and PLAYER_DIAGNOSTIC_TAG.

**Spec:** docs/superpowers/specs/2026-11-21-playback-startup-best-match-design.md

## Global Constraints

- Preserve the Baseline Profile, benchmark harness, Liquid Glass, biometric, and platform-specific code.
- Do not change stream score weights, codec/resolution preferences, network buffer thresholds, retries, cache TTLs, or fallback timing.
- Keep Media3 instrumentation Android-only; do not change iOS or mpv behavior.
- Never log raw signed URLs, query parameters, credentials or request headers.
- Do not claim performance gains without repeatable physical-device measurements.
- No builds or CI unless explicitly authorized.

## Review Focus

- Filters remove every candidate: Best Match must be null.
- Instant Only excludes unknown and non-cached streams from Best Match exactly as it does from the visible list.
- Provider filtering (`selectedFilter`) excludes other providers from Best Match.
- Addon groups with multiple streams and stable original ordering preserve tie-break behavior.
- Media loads with retries or repeated HLS playlist refreshes do not create unbounded diagnostic logging or alter error handling.
- Load callbacks arriving after a new media item must not be interpreted as proof that the current media item completed its own load.

---

### Task 1: Best Match correctness

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/SmartStreamRanking.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/streams/SmartStreamRankingTest.kt`

**Interfaces:**
- Consumes: `StreamsUiState.filteredGroups`, `SmartStreamRanking.matches`, `SmartStreamQuality.cached`.
- Produces: unchanged `SmartStreamsResult.best`, selected from `allState` after provider and Smart Streams filters, intentionally independent of the free-text list search.

- [x] Add regression tests for resolution filters, Instant Only with an unresolved torrent, provider selection, and independence from the free-text list search.
- [ ] Run the tests to verify they fail against the unmodified implementation.
- [x] Apply Smart Streams filters independently to the list and full source state; use the latter plus `selectedFilter` for Best Match.
- [ ] Run the focused test, the full common test suite and the relevant Android host tests.
- [ ] Review tie handling and verify no change to score or sort weights.

### Task 2: Android first-load diagnostics

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt`

**Interfaces:**
- Consumes: Media3 1.8.0 `AnalyticsListener.onLoadStarted/onLoadCompleted`, `LoadEventInfo`, `MediaLoadData`, existing `PlaybackDiagnostics`.
- Produces: bounded log events tagged `NuvioPlayerDiag` for first manifest/media load and load completion.

- [x] Verify exact APIs and constants against Media3 1.8.0 source.
- [x] Log only first manifest and first media load-start events; ignore retries for first-load classification.
- [x] Log only the first manifest and first media load completion, avoiding per-segment log flooding.
- [x] Discard negative elapsed timestamps before consuming the first-event guard.
- [ ] Correlate `EventTime` event-specific timeline/window/period identity with current player identity; do not trust the current `attempt` counter for delayed or pre-buffered events.
- [x] Route all six `exoPlayer.prepare()` call sites through one explicit diagnostic attempt boundary, including retry, Blu-ray title/recovery and external-subtitle reloads.
- [ ] Distinguish video, secondary audio and subtitle source load events when `MergingMediaSource` is used; do not treat the first merged media event as necessarily the video load.
- [ ] Reset first-load guards only at the corresponding verified prepare-attempt boundary.
- [ ] Compile the Android target and inspect sample diagnostics after playback on a physical device.

### Task 3: Evidence and notes

**Files:**
- Modify: `docs/engineering/player-stream-startup-optimization-2026-11-19.md`
- Modify: `docs/engineering/codebase-improvement-notes.md`

- [ ] Record Best Match filter behavior and preserved scoring semantics.
- [ ] Document log event field meanings, privacy redaction, and diagnostic bounds.
- [ ] Record verification and measurement status accurately, including the absence of runtime results until measured.
- [ ] Re-fetch all changed files from the remote branch and inspect the complete diff.
- [ ] Request independent code review before merge.


### Task 4: HLS inspection latency regression coverage

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/HlsParameterSetInitDataSource.kt` only after the tests establish the desired latency/fallback contract.
- Test: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/HlsParameterSetInitTest.kt` or a focused DataSource host test beside it.

**Confirmed current behavior:** eligible finished clear playlists synchronously wait up to 3 seconds for optional segment-head inspection after the playlist body has been read. Existing timeout coverage tests an interruptible worker, not a slow/non-interruptible DataSource.

- [x] Add a focused test for explicit timeout-outcome reporting; use the timeout callback rather than elapsed-duration comparison.
- [ ] Add controllable DataSource tests for healthy fast segment, slow segment timeout, interruption, close, and original-playlist fallback.
- [ ] Preserve SPS/PPS repair on affected VOD while proving optional inspection does not introduce an unbounded or unnecessary critical-path delay.
- [ ] Measure before changing the 3-second policy; do not select an arbitrary smaller timeout.

**Current evidence:** source inspection only. No code/test change, test execution, build, CI, fault injection or device measurement.


### Task 5: Runtime VOD cache-limit updates

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlaybackBuffering.android.kt`
- Tests: add focused Android host tests for the cache evictor/owner lifecycle.
- Inspect: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt`

**Confirmed defect:** the singleton `PlaybackDiskCache` creates a `LeastRecentlyUsedCacheEvictor` once. Its maximum is final in Media3 1.8.0, and settings setters do not update the active cache. The UI can therefore display a new size while the active cache continues enforcing the old one.

- [x] Add Android host regression tests for lowering the active limit (immediate eviction), increasing the limit (subsequent writes), LRU touch ordering, auto-size calculation/clamps, and known/unknown write-length reservation behavior.
- [x] Implement a mutable LRU `CacheEvictor` without opening a second `SimpleCache` for the same directory; observe relevant settings changes and apply the new limit to the existing cache.
- [x] Review cache/evictor lock ordering against the exact Media3 1.8.0 `SimpleCache` callbacks; never hold the evictor state lock while calling `Cache.removeSpan`.
- [ ] Execute the focused Android host tests and exercise concurrent active `CacheDataSource` reads/writes before merge. The source test is added but unexecuted; no Gradle task, APK build or CI was run in this session.
