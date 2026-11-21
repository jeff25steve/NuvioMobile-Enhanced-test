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
- [ ] Reset first-load guards at a verified prepare-attempt boundary if the diagnostics are intended to cover repeated prepares on the same ExoPlayer.
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
