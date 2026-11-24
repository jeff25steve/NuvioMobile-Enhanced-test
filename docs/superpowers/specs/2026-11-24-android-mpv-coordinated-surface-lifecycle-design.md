# Android libmpv Coordinated Surface Lifecycle Design

**Date:** 2026-11-24  
**Repository:** `jeff25steve/NuvioMobile-Enhanced-test`  
**Target branch:** `main-build-testing`  
**Status:** Approved design; implementation not yet started

## Intent

Replace the current loosely coordinated libmpv surface teardown with one lifecycle owner that serializes native-player operations and prevents stale surface work from racing destruction or final release. The goal is correct SurfaceView/TextureView lifetime handling across view removal, recreation, source transitions, backgrounding and PiP, without main-thread blocking or unnecessary playback-engine changes.

## Evidence and prior art

- Android's `SurfaceHolder.Callback.surfaceDestroyed` contract requires the renderer to stop accessing the surface before the callback returns. SurfaceView callbacks normally run on the window/UI thread.
- Android's `TextureView.SurfaceTextureListener.onSurfaceTextureDestroyed` has a different ownership contract: returning `true` permits framework release; returning `false` transfers release responsibility to the application.
- Compose `AndroidView.onRelease` runs on the UI thread when the View leaves composition permanently.
- The current project uses a dedicated single-thread libmpv dispatcher, but `MpvSurfaceView.detach` schedules detach asynchronously and releases the surface after job completion. Final `releaseMpv()` separately schedules teardown and `mpv.close()`.
- The pinned `mpv-android-lib:0.2.2` source is available and corresponds to upstream library commit `ad0cee3dc001960086d089d9c5b2ccc9902b8339` (22 November 2026). Its JNI `detachSurface()` in `lib/src/androidMain/jni/render.cpp` sets `wid=0` and deletes the JNI global Surface reference immediately; it does not wait for VO teardown or emit a completion acknowledgement. `nativeDestroy()` joins the event thread and calls `mpv_terminate_destroy`, but destroys the entire core.
- Nuvio API 29+ does not pass `holder.surface` directly to mpv. It creates a child `SurfaceControl`, wraps it in `Surface(layer)`, and reparents the child to null during `surfaceDestroyed` before delayed native detach/release. Android documents that the parent SurfaceControl lifetime follows the SurfaceView surface. Therefore the safety question is the child-layer transaction/ownership ordering, not simply whether mpv is still using `holder.surface`. `Transaction.apply()` returning is not proof of commit.
- Upstream mpv-android `BaseMPVView` sets `vo=null`, disables `force-window`, then calls `detachSurface()`; its source explicitly notes that setting the property may not wait for video-output deinitialization and flags a possible race. The aniyomi fork likewise documents asynchronous detach. Just Player Pro uses the same sequence directly in its Media3 Player surface callbacks, so it is not a stronger completion strategy.
- Consequently, copying upstream's callback implementation is not a stronger solution. The app needs explicit lifecycle coordination around its own dispatcher and view ownership.

## Goals

1. Ensure all native libmpv calls affecting lifecycle are serialized on the existing libmpv execution context.
2. Prevent stale surface attach, resize or property operations from executing after surface destruction or final release begins.
3. Ensure final player close happens only after the active surface has been safely detached and owned surface resources released.
4. Make release idempotent and safe under repeated callbacks, Compose disposal and concurrent teardown requests.
5. Preserve PiP and background playback semantics: lifecycle pause must not be conflated with final player destruction.
6. Avoid blocking the main thread while coordinating native work.
7. Add deterministic regression tests for operation ordering and lifecycle races, plus Android integration coverage where feasible.

## Non-goals

- Replacing libmpv or changing the selected playback engine.
- Upgrading Media3, mpv-android or native AAR dependencies.
- Changing stream selection, seek behavior, decoder policy, rendering quality or playback UI.
- Claiming the native libplacebo sync-file fix is present or absent without verifying the exact shipped artifact.

## Approaches considered

### A. Keep current asynchronous detach and add delays/retries

Rejected. A delay does not establish that native rendering has stopped and remains timing-dependent. It does not serialize final release with all pending surface operations.

### B. Copy upstream mpv-android BaseMPVView teardown

Rejected as insufficient. Upstream itself documents that changing `vo` may not wait for video-output deinitialization, leaving a race before detach. It also does not model this app's Compose disposal, custom dispatcher and queued operations.

### C. Coordinated lifecycle owner with serialized operations and surface generations

Selected. Retain the existing dispatcher and native player, but make one lifecycle owner authoritative for surface state and final release. Every attach/resize/detach operation is ordered; a generation token invalidates stale queued operations; release transitions atomically to a terminal state and closes mpv only after teardown completes. Surface callbacks use a non-blocking handoff protocol that must satisfy Android's callback contract. No arbitrary sleeps and no main-thread `runBlocking`.

## Architecture decisions

- Model lifecycle states explicitly (at minimum: uninitialized, active-without-surface, surface-attached, releasing, released); exact state representation is an implementation decision.
- Associate every surface creation with a monotonically increasing generation or equivalent identity token.
- Route all lifecycle-sensitive mpv calls through one serialized executor/dispatcher. Avoid introducing a second independent command queue.
- Invalidate the current generation synchronously when destruction begins so already-queued stale attach/resize work becomes a no-op.
- Teardown order must be based on the native API's actual completion semantics, not merely command submission. Investigate available mpv events/properties or JNI behavior before choosing the completion signal.
- If the native API cannot provide a reliable non-blocking completion signal compatible with SurfaceHolder's synchronous destruction contract, stop and document that limitation rather than shipping a delay or unsafe blocking wait. Evaluate whether a supported ownership strategy (including TextureView retention where applicable) is needed.
- Separate pause/background/PiP policy from terminal release. ON_STOP may pause; it must not close the player when PiP or background playback requires retention.
- Keep observer removal, coroutine cancellation, subtitle/now-playing cleanup and native close in an explicitly ordered final-release path.
- Do not change public playback-controller behavior unless required by the lifecycle contract.

## Acceptance criteria

- A surface generation invalidated by destruction cannot be reattached by stale queued work.
- Repeated destroy/release requests do not double-detach, double-release or double-close.
- Native close cannot run before the surface teardown completion condition.
- No blocking wait is introduced on the UI thread.
- PiP/background transitions preserve current intended playback behavior.
- Tests cover attach-vs-destroy, resize-vs-destroy, release-vs-detach, repeated release, rapid recreation and source replacement.
- Existing Android surface integration test remains applicable and is expanded only where it gives meaningful coverage.
- No dependency upgrade, unrelated playback refactor, build or CI execution is included in this change.
- The exact teardown completion primitive is proven from the pinned mpv-android/JNI implementation before production changes are made.

## Risks and explicit stop conditions

The original plan's blanket requirement that SurfaceView's callback synchronously wait for mpv detach is too broad for this code: the API 29+ path renders to an app-owned child SurfaceControl, not the SurfaceView's own holder Surface. The design must first prove whether reparenting the child to null within the callback, retaining the child SurfaceControl/Surface until detach is safe, and releasing it after the serialized detach completes is sufficient. Do not add a main-thread wait merely because the callback is named `surfaceDestroyed`; equally, do not assume `Transaction.apply()` is a commit acknowledgement. The TextureView path has its own explicit ownership transfer when returning false.

The key unresolved technical risk is whether the pinned mpv-android 0.2.2 API offers a completion primitive that proves the video output has stopped using a Surface before the Android callback returns. The upstream Java/Kotlin method signature alone is not proof of synchronous native completion. Inspect the exact pinned source/JNI implementation and artifact provenance. If no safe completion mechanism exists, do not paper over it with sleeps, main-thread blocking or assumptions; report the limitation and propose the least risky supported ownership alternative.

## Verification boundary

No builds, tests, device runs or CI workflows have been started. They require separate explicit authorization. Code review and static inspection do not substitute for runtime validation of native surface teardown.
