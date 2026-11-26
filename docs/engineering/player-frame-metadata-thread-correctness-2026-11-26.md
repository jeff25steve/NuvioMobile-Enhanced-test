# ExoPlayer frame-metadata wrong-thread failure — 26 November 2026

**Branch:** `main-build-testing`  
**Affected path:** Android ExoPlayer video-frame diagnostics  
**Relevant source:** `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt`  
**Pinned dependency:** AndroidX Media3 1.8.0  
**Original runtime evidence:** user-supplied playback log / stack trace dated 26 November 2026

## 1. Observed failure

The playback log showed:

```text
IllegalStateException: Player is accessed on the wrong thread.
Current thread: 'ExoPlayer:Playback'
Expected thread: 'main'
```

The important stack frames were:

```text
ExoPlayerImpl.verifyApplicationThread
ExoPlayerImpl.getCurrentPosition
FrameMonitoringPlayerView.setPlayer$lambda$0
VideoFrameMetadataListener.onVideoFrameAboutToBeRendered
MediaCodecVideoRenderer.processOutputBuffer
ExoPlayerImplInternal.doSomeWork
```

This localized the failure to the custom frame-metadata callback, not to stream selection, the decoder, Gradle, Java or the Android packaging workflow. The prior callback called `nextPlayer.currentPosition` directly from `VideoFrameMetadataListener`.

## 2. Version-specific API contract

The current branch is pinned to Media3 **1.8.0**, because its seven local custom Media3 AARs must remain API-aligned. The 22–23 November 1.11.1 / 1.12.0-beta01 experiments were reverted on 24 November. The live version catalog, custom-AAR fingerprint guard and 24 November restoration commits establish the active baseline. Do not apply later-version API advice to this branch without a coordinated custom-AAR migration.

The exact AndroidX 1.8.0 source establishes the threading contract:

- `VideoFrameMetadataListener.onVideoFrameAboutToBeRendered` is called on the playback thread.
- ExoPlayer player methods must be called from one application thread. In this app, `PlayerView` requires the main application looper.
- `ExoPlayerImpl.getCurrentPosition()` calls `verifyApplicationThread()`.
- `setVideoFrameMetadataListener()` and `clearVideoFrameMetadataListener()` also verify the application thread. These are the correct listener registration/removal methods for the pinned 1.8.0 API.
- The newer `addVideoFrameMetadataListener()` / `removeVideoFrameMetadataListener()` API is not the method pair to copy into the 1.8.0 implementation.

Primary references:

- [Media3 1.8.0 VideoFrameMetadataListener source](https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/VideoFrameMetadataListener.java)
- [Media3 1.8.0 ExoPlayerImpl source](https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImpl.java)
- [Media3 1.8.0 ExoPlayer threading contract](https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java)
- [Media3 1.8.0 PlayerView main-thread contract](https://github.com/androidx/media/blob/1.8.0/libraries/ui/src/main/java/androidx/media3/ui/PlayerView.java)
- [Android troubleshooting: Player accessed on the wrong thread](https://developer.android.com/media/media3/exoplayer/troubleshooting)

## 3. Integrated correction

Commit `3c79e5dd88e72a5d7f051982014498f28fbc78d1` replaced direct player access inside the renderer callback with a thread-safe state handoff:

- `VideoFramePlaybackContext` is an immutable snapshot of prepare attempt, expected/current media identity and the last application-thread-published playback position.
- `VideoFramePlaybackContextStore` holds that snapshot in `AtomicReference`. The application thread publishes; the playback callback reads.
- `beginExoPlayerPrepareAttempt()` publishes the attempt and expected identity before prepare.
- `dispatchExoPlayerSnapshot()` refreshes the current identity and position through normal application-thread player access.
- The frame callback now receives only renderer-provided metadata (presentation timestamp, monotonic elapsed time and frame rate), reads the immutable snapshot and records a frame only when the snapshot says it belongs to the expected media item.
- The watchdog's state methods are synchronized. It is diagnostic-only: it does not change playback, choose a decoder, retry or switch engines.

The failed run #140 then identified a separate compile-time inconsistency: `updateFrameMetadataCallback` still had the old five-parameter function type after the callback had moved to three parameters. Commit `761995c639b643a923e7b23a34b2250feafcefda` corrected the remaining signature to:

```kotlin
(Long, Long, Float) -> Unit
```

The constructor, stored callback, update method, callback invocation and AndroidView call sites now agree on that three-argument contract. No ExoPlayer object or getter is passed into the frame callback.

## 4. Dependencies and source compatibility

No dependency was added or upgraded for this correction.

- `AtomicReference` is part of the Java standard library; no coroutine, AndroidX or third-party dependency is required for the handoff.
- `VideoFrameMetadataListener`, `setVideoFrameMetadataListener` and `clearVideoFrameMetadataListener` already exist in the pinned Media3 1.8.0 baseline.
- The current local Media3 AAR family and its Maven exclusions were left unchanged.
- The current custom-AAR guard is `scripts/tests/test-media3-custom-aar-compatibility.sh`; it validates catalog version, minSdk 24, all seven AAR fingerprints, runtime support dependencies and the absence of post-1.8.0 API references.
- The source-level regression tests are `VideoFramePlaybackContextTest` (snapshot publication, update preservation and stale identity rejection) and the frame-gap cases in `ExoVideoStartupWatchdogTest`.

## 5. Workflow and verification evidence

The source correction was committed on `main-build-testing` as `761995c639b643a923e7b23a34b2250feafcefda`.

- [Android run #140](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/45886003595) failed in `:composeApp:compileAndroidMain` on the obsolete five-parameter callback signature. This was a Kotlin type mismatch, not a missing dependency.
- [Android run #141](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/45892214232) checked out that exact source commit. At the last checked state, checkout, freshness guard, Java/Gradle verification, runtime setup, script validation, Android host regression tests, Rust setup and Cargo cache steps had passed. The full profile-capture APK pair and then the Play Store profile-capture pair had advanced through the workflow. The run was still in progress; this document does **not** claim the complete workflow or all APK groups passed.
- The host regression-test workflow step passed on run #141. This is useful compile/test evidence for the Android host test target, but it is not a physical-device playback test.

## 6. External implementation and community comparison

The official API and exact 1.8.0 source are decisive for the thread rule. The community discussions agree:

- [AndroidX Media issue #2416](https://github.com/androidx/media/issues/2416) (2025) maintainer guidance describes the frame callback as the place to receive presentation and scheduled release timestamps, and notes the main integration caveats for custom player loopers.
- [Legacy ExoPlayer issue #5860](https://github.com/google/ExoPlayer/issues/5860) includes maintainer guidance that the callback is on the playback thread and should not perform blocking work.
- [AndroidX Media issue #2884](https://github.com/androidx/media/issues/2884) is a separate Media3 1.8.0 wrong-thread report involving a non-main application looper and service lifecycle. It reinforces the distinction between application and playback loopers; it is not the cause of Nuvio's frame callback crash.

An Exa search did not surface a credible, more authoritative Reddit/X implementation that improves on the official pattern. The adopted pattern is therefore the simplest version-correct one: keep Player access on its application looper and pass a minimal immutable/atomic snapshot to the playback-thread callback. No external project code was copied.

## 7. Remaining verification boundary

The source fix removes the exact forbidden getter call identified by the original stack trace, and the later callback-signature mismatch has been corrected. However:

- The Android run #141 must finish successfully before the full APK/profile-bundle workflow can be called green.
- Real playback on a physical Android device must still confirm that the original runtime exception no longer occurs with the affected stream and decoder path.
- The host test verifies snapshot state/identity policy; it does not instantiate Media3's renderer callback or assert the runtime thread itself.
- The AndroidView listener is also updated during player replacement. A detached player may have an already-queued frame callback; any future change to callback ownership must preserve per-player/attempt isolation without reintroducing Player getters on the playback thread.
- No Media3 upgrade, new dependency or decoder/retry behavior change is part of this correction.

Do not declare the playback problem fully runtime-validated until the current CI run completes and the original stream is exercised on-device with fresh logs.

## 8. Adjacent lifecycle hardening identified during review

A separate, lower-severity callback-ownership race remains a candidate for a follow-up:

- The frame listener currently reads `FrameMonitoringPlayerView.frameMetadataCallback`, a mutable volatile field, each time it is invoked.
- The AndroidView update block currently calls `updateFrameMetadataCallback(newCallback)` immediately before assigning `playerView.player = exoPlayer`.
- When the same PlayerView instance is reused while swapping ExoPlayer instances, a frame callback already queued by the old player could run in the interval after the callback field has been replaced but before the old metadata-listener removal has taken effect. In that narrow case it could be routed into the new callback closure and its new attempt snapshot.
- This does not reintroduce the original wrong-thread Player getter, and it does not itself stop video. It could, however, make the diagnostic frame-gap watchdog attribute a late old-player frame to the replacement player's attempt. The existing comment that says the listener captures `nextPlayer` no longer accurately describes the callback code after the thread fix.

A safe follow-up design is to give each attached listener its own callback holder (backed by `AtomicReference`), update the player binding before updating that holder in AndroidView's update block, and have the listener capture that holder rather than a view-wide mutable callback. That allows same-player recompositions to update the active holder while callbacks from a detached player's listener retain the old holder. Add host tests for both same-player callback updates and old/new holder isolation before changing production code.

**This follow-up is not committed.** The user's current request authorized verification of the player-thread fix, but not an additional source change that would trigger a new Android CI run. The current wrong-thread cause is addressed; this separate diagnostic-attribution race remains a hardening item and should be resolved in a separately authorized code/CI pass.
