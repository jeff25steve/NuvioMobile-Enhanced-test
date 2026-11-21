# Playback startup diagnostics and Best Match consistency — design

## Intent

Make Smart Streams recommend only sources that are actually visible under the user's current filters and provider selection. Improve diagnosis of Android ExoPlayer startup delay without making speculative changes to playback policy.

## Goals

- Best Match recommendation and visible stream list have the same candidate boundary.
- Existing Best Match score, score weights, stable order tie-break, and low-quality preference are preserved.
- Android logs distinguish initial manifest and media fetch starts/completions from decoder setup and first-frame release.
- URLs are redacted; no credentials or query secrets are logged.
- Diagnostic events are bounded and do not affect stream requests or playback behavior.

## Non-goals

- Changing source ranking weights or claiming Smart Streams is a real network speed test.
- Changing HLS adaptive selection, media buffer thresholds, DNS behavior, debrid TTLs, or fallback retry/poll behavior.
- Adding next-video preloading, multiple ExoPlayer instances, or background media fetching.
- Changing iOS or mpv playback.

## Constraints

- Target branch starts at `main-build-testing` SHA `61df653f87bbaef4158279ddb1664ab0fda1bad5`.
- Repository versions: Kotlin 2.4.10, AndroidX Media3 1.8.0, kotlinx.coroutines 1.10.2.
- Preserve user-owned Baseline Profile, benchmark, Liquid Glass and biometric work.
- No build or CI unless authorized; source-only status must not be presented as compile/test verification.

## Approaches

1. **Use the full source state with the same provider and Smart Streams filters (selected).** Apply Smart Streams filters to `allState.groups`, then use `allState.filteredGroups` for the Play Best candidate pool. Independently apply Smart Streams filters to `listState.groups` for the displayed list. This preserves the existing distinction between the source-wide Play Best card and the free-text search results below it.
2. Apply Smart Streams filters to `listState` and rank that projection. Rejected because `listState` is pre-filtered by the free-text search field, while Play Best sits above that field and is intended to remain a source-wide recommendation.
3. Keep ranking global and merely hide the recommendation when it doesn't match. Rejected because it can hide a valid best match even when a lower-ranked source matches the active provider/Smart Streams filters.

For diagnostics:

1. **Bounded Media3 AnalyticsListener events (selected).** Log initial manifest/media starts and completions with elapsed realtime, load duration, bytes, and track type, while retaining existing decoder and first-frame logs.
2. Full per-segment tracing. Rejected due to unbounded log volume and risk of sensitive data exposure.
3. Change load-control/buffer settings or prewarm additional players. Rejected until stage-level timings establish a bottleneck and tradeoffs can be measured.

## Design decisions

- The list projection is `listState.groups` after the free-text search, with Smart Streams filters applied; `listState.filteredGroups` then applies selected-provider filtering.
- The Best Match projection is `allState.groups` with the same Smart Streams filters, followed by `allState.filteredGroups` for selected-provider filtering. It intentionally ignores the lower-page free-text search, preserving the existing Play Best placement/behavior.
- Existing “best non-low-quality unless none exist” behavior and stable input order are retained after filtering.
- ExoPlayer instrumentation uses Media3 1.8.0 `AnalyticsListener` callbacks and `EventTime.realtimeMs`, which is elapsed realtime.
- Log data classification by `C.DATA_TYPE_MANIFEST` and `C.DATA_TYPE_MEDIA`. First-start and first-completion events are limited to one per type per player-listener lifetime. Negative elapsed timestamps are discarded so callbacks from before the current prepare are not labelled as the new attempt.
- All URIs pass through existing `InAppLogger.redactUrl`.
- Media3 first-frame callback indicates codec buffer release, not confirmation of physical display presentation. Keep the diagnostic name consistent with the existing firstFrame event.

## Open verification

- Source-level static review is possible through GitHub, but no checked-out workspace/build runner is available in this task session.
- The Android code and shared Kotlin must be compiled and regression tests run before merge.
- Before using load events for per-attempt startup analysis, correlate event-specific `timeline`, `windowIndex`, and `mediaPeriodId` with `currentTimeline`, `currentWindowIndex`, and `currentMediaPeriodId`, or otherwise bind the load to a unique prepare/media-item identity. Media3 can report events for a different item than the one currently playing.
- Physical-device measurements must compare multiple cold/warm playback attempts by media type and network. Do not change buffer/track/source policies until evidence points to a specific critical-path stage.
