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

### Task 1: Move native MPV initialization off the UI thread and prove lifecycle ordering

**Files:**
- Inspect: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/MpvSurfaceView.android.kt`
- Inspect: exact mpv-android 0.2.2 source/JNI and published AAR metadata for `MPV.attachSurface`, `MPV.detachSurface`, `MPV.command`, `MPV.close`.
- Update: `docs/superpowers/specs/2026-11-24-android-mpv-coordinated-surface-lifecycle-design.md`

**Interfaces:**
- Keep Android View creation and callbacks on the UI thread.
- Route MPV construction, config/cache setup, init options, property observation and later MPV calls through the existing `mpvDispatcher`.
- Produce explicit initialized/failed state that gates queued attach/load/property work.

- [x] Inspect pinned artifact source and release provenance. The 0.2.2 source artifact is published 22 November 2026 and the matching library branch head is `ad0cee3dc001960086d089d9c5b2ccc9902b8339`.
- [x] Confirm `MPV.android.kt` constructs the native core synchronously in its initializer (`nativeCreate`, `nativeInit`, session init and options). Confirm Nuvio's `AndroidView(factory)` runs on the UI thread and currently invokes `Utils.copyAssets` and `initialize` there.
- [x] Inspect JNI `render.cpp`: detach sets `wid=0`, deletes the JNI global Surface reference, and returns without a completion acknowledgement. Full `nativeDestroy` joins the event thread and destroys the core; it is not a surface-only completion primitive.
- [x] Compare upstream mpv-android, Just Player Pro and Wholphin. Wholphin's merged PR #481 establishes a dedicated command thread to avoid UI-thread libmpv mutex waits; Nuvio already has the equivalent single-thread dispatcher, so do not add a duplicate HandlerThread.
- [ ] Add deterministic tests for callback-before-init-completion, initialization failure, release-during-init, attach/load ordering and repeated initialization/release.
- [x] Move native construction, asset preparation, init options and property observation to the existing dispatcher; add explicit initialized/failed state and complete/fail pending work deterministically.

Implementation note (25 November): MpvSurfaceView.initialize now performs asset preparation, lazy MPV construction, configuration, initial options and property observation on the existing mpvDispatcher. The Android SurfaceView/TextureView is still created on Dispatchers.Main.immediate. CoroutineStart.UNDISPATCHED guarantees the initialization task is enqueued before Compose can enqueue observer/property operations. The initialized gate prevents queued commands from touching an uninitialized/failed native instance; initialization failure completes surfaceReady exceptionally and loadSource exits without hanging. Observer registration/removal and codec diagnostics are serialized through the same dispatcher. No surface-teardown completion claim is made.
- [ ] Verify Android API 29+ child SurfaceControl reparent transaction ordering and native ownership. Nuvio wraps an app-owned child layer in `Surface(layer)`; it does not attach `holder.surface`. Prove whether the child remains valid until delayed detach completes and whether the parent can be destroyed before the reparent transaction commits.
- [ ] Inspect whether mpv emits a documented event/property that proves VO teardown; do not treat `detachSurface()` return or `Transaction.apply()` return as completion.
- [ ] Record the proof and update acceptance criteria. If neither the child-layer ownership model nor an event-based completion signal can be proven, stop and report the limitation; do not add arbitrary sleeps or an unbounded UI-thread wait.

### Task 2: Add deterministic lifecycle state-machine tests

**Files:**
- Extend: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/MpvSurfaceReadinessTest.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/MpvSurfaceView.android.kt` only after tests define the state contract.

**Interfaces:**
- Introduce a small internal lifecycle coordinator/state machine with injectable operation hooks so tests can control queued attach/detach/close completion without loading native mpv.
- Required operations: surface-created/attach, surface-changed/resize, surface-destroyed/detach, final-release.

- [x] Add deterministic readiness-gate coverage for successful attach, failed attach propagation, and release cancellation. Tests are in MpvSurfaceReadinessTest.kt; they have been authored but deliberately not executed because build/test execution is not authorized.

Implementation note (25 November): MPV surface attachment now reports thrown native/JNI failures through the shared readiness signal. The playback coroutine no longer silently swallows a failed readiness await; it propagates non-cancellation failures to the existing player error callback and diagnostic log. Cancellation is still rethrown. This prevents a failed attach from leaving playback stuck indefinitely without surfacing an error. At this checkpoint, detach completion and generation races remained open; the subsequent implementation note below supersedes that status.

- [ ] Write failing tests for attach queued then destroyed; old generation must never attach after invalidation.
- [ ] Write failing tests for resize queued then destroyed; stale resize must be ignored.
- [ ] Write failing tests for repeated destroy and repeated release; native release actions occur at most once.
- [ ] Write failing tests for release requested during detach; close occurs only after detach completion.
- [x] Author host coverage for invalidated generation rejection, stale-destroy isolation and terminal release. Tests are committed but remain unexecuted; they do not yet prove native detach completion or resource release ordering.
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
- [x] Invalidate a surface generation synchronously before destruction queues native work; attach and resize operations reject stale generations on the MPV dispatcher.
- [x] Make final release terminal and idempotent; reject new surface generations and queued native operations after release starts.
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
- [x] Reject stale attach/resize generations, make failed detach cleanup wait for final native close, and return framework texture ownership when no app-owned generation was created. Full native detach completion and repeated callback/device validation remain open.
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

Historical execution note: the first checkpoint correctly treated native detach completion and child SurfaceControl ownership as blockers. The subsequent event-based VO barrier and child-layer handoff recorded below supersede that checkpoint. The existing Android integration test still does not prove the new native event barrier; host tests are authored but runtime validation remains outstanding.


Intermediate checkpoint (25 November): SurfaceView and TextureView callbacks gained surface generations and stale attach/resize rejection. At that stage native VO teardown completion was still unproven; this was superseded by the event-based barrier and child-surface ownership protocol below.

Surface operation handling was hardened at the same boundary: negative return codes from the runtime surface property/option setters now become failures; detach attempts the native detachSurface() even if a preceding property update fails, but preserves the failure so the owning cleanup job does not release Java Surface/SurfaceControl resources on a failed detach request. Failed resource releases are retained until final native close succeeds. This is a conservative lifetime fallback, not a native detach acknowledgement.

This checkpoint's unresolved native detach limitation was superseded by the event-based VO teardown barrier and child-surface ownership protocol recorded below.


Implementation note (25 November, final slice): A failure during a later surface reattachment is forwarded through the existing player error callback; the initial attach failure still propagates through the readiness deferred to the source-load error path. TextureView now returns framework ownership when no application-owned texture generation was created, while retained textures continue to return false for deferred app release. This initial callback correction was followed by the event-based native teardown barrier recorded in the completed source-level lifecycle slice below.
