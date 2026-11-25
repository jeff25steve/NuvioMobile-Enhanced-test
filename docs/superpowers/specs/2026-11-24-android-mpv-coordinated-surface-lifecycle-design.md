# Android libmpv Coordinated Surface Lifecycle Design

**Date:** 2026-11-24  
**Repository:** `jeff25steve/NuvioMobile-Enhanced-test`  
**Target branch:** `main-build-testing`  
**Status:** Source-level lifecycle implementation landed on `main-build-testing`; host tests authored but unexecuted; native AAR/device validation remains outstanding

## Intent

Replace the current loosely coordinated libmpv surface teardown with one lifecycle owner that serializes native-player operations and prevents stale surface work from racing destruction or final release. The goal is correct SurfaceView/TextureView lifetime handling across view removal, recreation, source transitions, backgrounding and PiP, without main-thread blocking or unnecessary playback-engine changes.

## Evidence and prior art

- Android's `SurfaceHolder.Callback.surfaceDestroyed` contract requires the renderer to stop accessing the surface before the callback returns. SurfaceView callbacks normally run on the window/UI thread.
- Android's `TextureView.SurfaceTextureListener.onSurfaceTextureDestroyed` has a different ownership contract: returning `true` permits framework release; returning `false` transfers release responsibility to the application.
- Compose `AndroidView.onRelease` runs on the UI thread when the View leaves composition permanently.
- The current project uses a dedicated single-thread libmpv dispatcher, but `MpvSurfaceView.detach` schedules detach asynchronously and releases the surface after job completion. Final `releaseMpv()` separately schedules teardown and `mpv.close()`.
- The pinned `mpv-android-lib:0.2.2` source is available and corresponds to upstream library commit `ad0cee3dc001960086d089d9c5b2ccc9902b8339` (22 November 2026). Its JNI `detachSurface()` in `lib/src/androidMain/jni/render.cpp` sets `wid=0` and deletes the JNI global Surface reference immediately; it does not wait for VO teardown or emit a completion acknowledgement. `nativeDestroy()` joins the event thread and calls `mpv_terminate_destroy`, but destroys the entire core.
- Nuvio API 29+ does not pass `holder.surface` directly to mpv. It creates a child `SurfaceControl`, wraps it in `Surface(layer)`, then hides and reparents the child to null inside `surfaceDestroyed`. AOSP `SurfaceView.updateSurface()` calls `notifySurfaceDestroyed()` before `releaseSurfaces()` submits removal of the parent control. The child producer is retained until the native VO completion barrier. The callback does not wait for native teardown and does not treat `Transaction.apply()` return as a commit acknowledgement.
- The pinned mpv-android JNI `detachSurface()` is still a void method that sets `wid=0` and deletes its JNI global Surface reference immediately. The app now waits for a separate libmpv completion signal first: the runtime `vo` option is marked `UPDATE_VO`; mpv's `uninit_video_out()` calls `vo_destroy()` (which joins the VO thread), clears `video_out`, then emits `MPV_EVENT_VIDEO_RECONFIG`; the `current-vo` property returns null when no VO exists. The app registers an `MPV.EventObserver`, sets `vo=null`, waits for `VIDEO_RECONFIG`, then checks `current-vo` on the native dispatcher. It calls JNI detach only after the old Android VO is gone or the null VO is selected.
- The mpv-android 0.2.2 build script shallow-clones `mpv-player/mpv` master without recording a fixed mpv commit in the published source metadata. The inspected mpv source commit confirms the event/property ordering, and the public event contract is documented, but the exact mpv core revision inside the Maven AAR is not independently attested. Runtime validation against the shipped AAR remains required.
- Upstream mpv-android `BaseMPVView` sets `vo=null`, disables `force-window`, then calls `detachSurface()`; its source explicitly notes that setting the property may not wait for video-output deinitialization and flags a possible race. The aniyomi fork likewise documents asynchronous detach. Just Player Pro uses the same sequence directly in its Media3 Player surface callbacks, so it is not a stronger completion strategy.
- Consequently, copying upstream's callback implementation is not a stronger solution. The app needs explicit lifecycle coordination around its own dispatcher and view ownership.

## Goals

0. Move native MPV construction and startup configuration off the UI thread using the existing single-thread dispatcher; the Android View itself and its callbacks remain UI-thread-owned.

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

### C. Coordinated lifecycle owner with serialized operations, generations and a native VO barrier

Selected. Retain the existing dispatcher and native player. A surface-lifecycle Mutex serializes attach, resize, detach and final close; generation tokens invalidate stale queued operations. SurfaceHolder's callback hides and reparents the app-owned child layer away from its parent, while TextureView returns false and retains its SurfaceTexture. A coroutine then sets `vo=null` and waits for `MPV_EVENT_VIDEO_RECONFIG` plus a `current-vo` null/null-VO check before JNI detach and resource release. Final release uses the same barrier. No arbitrary sleeps and no main-thread `runBlocking`.

## Architecture decisions

- The current `AndroidView(factory)` runs on the UI thread and currently calls `Utils.copyAssets()` plus `MpvSurfaceView.initialize()` synchronously. The pinned `MPV.android.kt` constructor performs `nativeCreate()`, `nativeInit()`, and event-session initialization; Nuvio then runs all initial options and property observation synchronously. This is a confirmed cold-start main-thread workload. Move this native initialization/configuration to the existing `mpvDispatcher`, not a new thread. Enqueue it before adding the child surface view or before any operation that can attach/load; use explicit initialized/failed state so early callbacks queue safely and failures complete pending work deterministically.

- Model lifecycle states explicitly (at minimum: uninitialized, active-without-surface, surface-attached, releasing, released); exact state representation is an implementation decision.
- Associate every surface creation with a monotonically increasing generation or equivalent identity token.
- Route all lifecycle-sensitive mpv calls through one serialized executor/dispatcher. Avoid introducing a second independent command queue.
- Invalidate the current generation synchronously when destruction begins so already-queued stale attach/resize work becomes a no-op.
- Teardown order is based on the mpv video-output destruction event, not merely command submission. Register the event observer before setting `vo=null`; after `MPV_EVENT_VIDEO_RECONFIG`, query `current-vo` on the MPV dispatcher and complete only when the old Android VO is gone (property unavailable/null) or the null VO is active. Then call JNI `detachSurface()` to clear `wid` and delete its global Surface reference.
- Surface transitions use one Mutex in addition to the single-thread MPV dispatcher. A new attach cannot overtake a previous detach while it waits for the VO completion event. Each surface generation owns an immutable release closure for its exact Surface/SurfaceControl/SurfaceTexture objects.
- SurfaceHolder callbacks do not block. On API 29+, the player renders into an app-owned child SurfaceControl Surface; hide and reparent that child to null within the callback before the framework removes the parent. Keep the child producer alive until the native event barrier completes. TextureView callbacks return false for an app-owned texture and release it only after the same barrier.
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

The source-level barrier is implemented, but the published mpv-android build script does not pin the mpv git commit used to produce the AAR. The event contract and inspected `uninit_video_out()` ordering support the implementation, but the exact binary revision must be checked at runtime. The 5-second timeout is fail-closed: it does not call JNI detach or release the old surface wrapper. During final shutdown, `mpv.close()` remains the ultimate core teardown fallback; if the event barrier times out, JNI's global Surface reference may remain until process exit because `nativeDestroy()` does not delete `ctx->surface`. This exceptional path is logged and must be exercised against the shipped AAR before production sign-off.

## Verification boundary

Host regression tests for generations, readiness and the VO release gate have been authored but not executed. No Gradle task, build, device run or CI workflow has been started; separate explicit authorization is still required. Static inspection does not substitute for runtime validation against the shipped mpv-android AAR.
