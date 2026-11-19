# Android startup independent-read overlap — design

**Date:** 19 November 2026
**Branch:** `main-build-testing`

## Intent

Reduce the time spent in Android's pre-`setContent` critical phase without moving any Home-required data after the first frame or changing the Home readiness contract.

## Current behavior

After cached profiles are restored, `StartupPreloader.preloadCriticalPhase()` runs watched history, watch progress, Continue Watching preferences and the Home snapshot sequentially on `Dispatchers.IO`. The first two both initialize/read shared tracking-provider state and must remain ordered. The latter two read independent stores: Continue Watching preferences use their own SharedPreferences file, while the Home snapshot uses `AppFileCache`.

## Goals and non-goals

- Preserve the same four Home-critical reads and their failure isolation.
- Keep watched history and watch progress sequential because they share tracking-provider initialization.
- Start Continue Watching preferences and Home snapshot reads concurrently with that sequential work.
- Keep all blocking reads off Main and preserve structured cancellation.
- Do not add speculative feature prewarming, dependencies, storage migrations, or UI changes.
- Do not change iOS startup behavior or the first-frame readiness signal.
- Do not claim a measured TTID/TTFD gain without the authorized same-device benchmark.

## Approaches considered

1. **Keep all reads sequential.** Lowest concurrency risk, but leaves two independent stores idle while watched/progress restoration runs.
2. **Run all four reads concurrently.** More overlap, but races shared TrackingProviderRegistry/TrackingSettingsRepository initialization and adds unnecessary storage contention. Rejected.
3. **Overlap only the two independent reads.** Keep watched and progress ordered, while loading Continue Watching preferences and Home snapshot on separate IO children. Selected as the smallest bounded change.

## Implementation decisions

- Add a small internal structured-concurrency helper that launches independent reads on an injected dispatcher while executing the dependent sequence on the same dispatcher.
- Inject the dispatcher so the scheduling contract can be tested deterministically without Android storage.
- Preserve existing per-step `step()` error handling. Cancellation must still propagate.
- Use the existing Kotlinx Coroutines dependency; do not change versions.

## Validation boundary

Add a regression test proving both independent reads start while the dependent sequence is suspended. No Gradle test, build, device benchmark or CI is authorized in this task. Static review is not a substitute for compilation or a measured startup comparison.
