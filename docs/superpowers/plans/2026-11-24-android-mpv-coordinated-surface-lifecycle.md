# Android libmpv Coordinated Surface Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make libmpv surface teardown, recreation and final release race-safe across Android surface callbacks and Compose player disposal.

**Architecture:** Retain the existing single-thread `mpvDispatcher` and introduce one explicit lifecycle state owner around its operations. Surface identities/generations invalidate stale queued work; final release is serialized after the native detach completion condition and is idempotent. The design must not block Android's UI-thread callbacks.

**Tech Stack:** Kotlin, coroutines, Android SurfaceView/SurfaceHolder, TextureView/SurfaceTexture, Compose AndroidView, mpv-android 0.2.2 API/JNI.

**Spec:** `docs/superpowers/specs/2026-11-24-android-mpv-coordinated-surface-lifecycle-design.md`

## Global Constraints

- Preserve the current libmpv engine and the existing single-thread native dispatcher.
- Do not introduce a main-thread blocking wait, arbitrary sleep, or timing-based teardown.
- Do not close libmpv before surface teardown has a proven completion condition.
- Preserve PiP/background playback semantics; pause is not final release.
- Do not upgrade Media3, mpv-android, libplacebo or native AARs.
- No builds or CI without separate explicit user authorization.
- Keep documentation in the existing engineering notes and this spec/plan; do not create duplicate notes.
- Do not use `[skip ci]` in new commit messages.

## Review Focus

1. Destroy callback races an attach already queued on `mpvDispatcher` — stale attach must not happen.
2. Surface is destroyed while native VO still uses it — no callback may return while native access continues.
3. Final release races surface detach or queued resize — close must be last and exactly once.
4. TextureView destruction differs from SurfaceHolder destruction — texture ownership and return value must remain correct.
5. PiP/background transitions and source replacement — player retention and resume behavior must remain unchanged.

---

### Task 1: Prove the pinned native API's teardown completion semantics

**Files:**
- Inspect: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/MpvSurfaceView.android.kt`
- Inspect: exact mpv-android 0.2.2 source/JNI and published AAR metadata for `MPV.attachSurface`, `MPV.detachSurface`, `MPV.command`, `MPV.close`.
- Update: `docs/superpowers/specs/2026-11-24-android-mpv-coordinated-surface-lifecycle-design.md`

**Interfaces:**
- Produces: a documented, source-backed answer to whether detach is synchronous, what native event/property proves VO deinitialization, and whether a safe completion signal is available.

- [ ] Inspect pinned artifact source, JNI implementation and release provenance; do not infer behavior from Kotlin method signatures.
- [ ] Compare the implementation with upstream mpv-android and Android SurfaceHolder/TextureView contracts.
- [ ] Record exact source paths, revisions and evidence in the design doc.
- [ ] If no reliable completion signal exists, stop implementation and report the blocking limitation with alternatives; do not proceed with sleeps or UI-thread blocking.

### Task 2: Add deterministic lifecycle state-machine tests

**Files:**
- Create or extend: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/MpvSurfaceLifecycleTest.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/MpvSurfaceView.android.kt` only after tests define the state contract.

**Interfaces:**
- Introduce a small internal lifecycle coordinator/state machine with injectable operation hooks so tests can control queued attach/detach/close completion without loading native mpv.
- Required operations: surface-created/attach, surface-changed/resize, surface-destroyed/detach, final-release.

- [ ] Write failing tests for attach queued then destroyed; old generation must never attach after invalidation.
- [ ] Write failing tests for resize queued then destroyed; stale resize must be ignored.
- [ ] Write failing tests for repeated destroy and repeated release; native release actions occur at most once.
- [ ] Write failing tests for release requested during detach; close occurs only after detach completion.
- [ ] Write failing tests for rapid generation A destroy then generation B create; A completion must not release B resources.
- [ ] Run only the targeted host-test task if separately authorized; otherwise leave tests unexecuted and report that boundary.

### Task 3: Implement serialized surface lifecycle ownership

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/MpvSurfaceView.android.kt`
- Test: lifecycle coordinator tests from Task 2.

**Interfaces:**
- The lifecycle coordinator owns state transitions and generation identity.
- All mpv lifecycle-sensitive calls execute through the existing `mpvDispatcher`.
- Existing public/protected player APIs remain unchanged unless a narrowly required internal signature change is proven necessary.

- [ ] Replace independent `destroyJob`/boolean coordination with the tested lifecycle state model.
- [ ] Ensure attach, resize, detach and close share one ordered command path.
- [ ] Invalidate a surface generation synchronously before destruction queues native work.
- [ ] Make final release terminal and idempotent; reject all new work after release starts.
- [ ] Use the proven native completion primitive from Task 1 before releasing native surface wrappers or closing mpv.
- [ ] Preserve SurfaceControl frame-rate cleanup and ensure old-generation cleanup cannot clear a newer layer.
- [ ] Keep all main-thread callbacks non-blocking; if the SurfaceHolder contract cannot be met with the available native completion mechanism, stop and revisit the ownership approach rather than shipping an unsafe compromise.

### Task 4: Correct SurfaceView and TextureView callback ownership

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/MpvSurfaceView.android.kt`
- Test: lifecycle coordinator tests and existing Android integration test.

**Interfaces:**
- SurfaceView and TextureView use separate callback adapters over the same lifecycle coordinator.
- Each adapter must retain and release only the surface resources it owns.

- [ ] SurfaceView callback invalidates the exact generation and guarantees native rendering no longer accesses the surface before callback return.
- [ ] TextureView callback follows its boolean ownership contract; returning `false` means the app releases the retained SurfaceTexture only after native detach is complete.
- [ ] Handle callback order variations and repeated callbacks without double release.
- [ ] Verify SurfaceControl reparent/visibility/frame-rate operations cannot outlive their layer generation.

### Task 5: Coordinate Compose and player lifecycle

**Files:**
- Modify only if evidence requires: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt`
- Inspect: `androidApp/src/androidTest/kotlin/com/nuvio/android/MpvSurfaceTest.kt`

**Interfaces:**
- `AndroidView.onRelease` remains the terminal view-disposal boundary.
- Lifecycle ON_STOP pause/PiP handling remains distinct from final release.
- Source replacement must not accidentally close a still-owned player or permit commands to target a released instance.

- [ ] Trace source replacement, Compose disposal, Activity stop/start, PiP and background paths against the coordinator.
- [ ] Make only the minimum changes needed to route terminal release through the same coordinator.
- [ ] Preserve controller, observer, subtitle, now-playing and audio-focus cleanup ordering.
- [ ] Add regression coverage for PiP/background pause-resume and view recreation where practical.

### Task 6: Review, documentation and integration readiness

**Files:**
- Update: `docs/engineering/codebase-improvement-notes.md`
- Update: this design and plan with final decisions and evidence.

- [ ] Review all changed code for races, deadlocks, duplicate release, leaked Surface/SurfaceControl objects and stale jobs.
- [ ] Confirm no new dependency or public API surface was added unnecessarily.
- [ ] Verify both `main-build-testing` and `ci/main-build-testing-20261124` before any authorized branch integration.
- [ ] Do not start builds, tests or CI without separate explicit authorization.
- [ ] Commit only after review; use clean descriptive messages and no `[skip ci]`.
- [ ] Report runtime validation still outstanding if no device/build authorization is given.

## Execution notes

Task 1 is a hard gate. The lifecycle refactor must not begin until exact pinned native behavior is established. Tasks 2–5 may then be implemented in small test-first increments. The existing Android integration test exercises seek, activity recreation and view removal/recreation, but it does not prove native detach completion or race safety; host tests must cover ordering deterministically.
