# Reddit-inspired playback improvements — design

**Date:** 25 November 2026  
**Target branch:** `main-build-testing`  
**Repository baseline:** `0258adbeaf0e6862d501b349f1486d90bb98ecba`

## Intent

Adapt the most useful engineering principles from Reddit's Android video-player rewrite to Nuvio's existing Android/hybrid playback implementation. Improve visible seek responsiveness without destabilising Nuvio's specialised ExoPlayer, libmpv, subtitle, audio, Blu-ray, buffering and lifecycle paths.

## Current project evidence

- The Android ExoPlayer path is a single full-screen content player with custom renderers, track selection, load control, audio processing, Blu-ray MediaSources, subtitle integrations, decoder fallback, frame diagnostics and a Compose-owned PlayerView.
- ExoPlayer snapshots intentionally publish at 250 ms while playing. The shared screen uses derived state and non-observed snapshots to limit broad recomposition.
- The main timeline and legacy seekbar both consume the snapshot position directly, so their visible thumb/track advances at the 4 Hz snapshot cadence.
- Nuvio does not keep a cache of reusable MediaSource instances. Blu-ray sources and merged secondary-audio sources are created for each prepare path. No instance of Reddit's cross-player MediaSource reuse defect was established.
- Nuvio's selected progressive DataSource is lazily constructed from `DataSource.open()` on the Media3 Loader worker. The previous cache-initialisation proposal was deliberately rejected after inspecting Media3 1.8.0 `SimpleCache`: its constructor starts its own initialisation thread and does not wait for the full directory/index scan.
- The repository contains a static compatibility guard that fingerprints all seven custom Media3 AARs and enforces Media3 1.8.0, minSdk 24 and known API constraints. A prior 1.12.0-beta01 experiment was superseded. The custom AARs are not rebuilt from a currently verified source fork in this task.

## Goals

- Make the visible seekbar position advance on display frames while playback is active.
- Keep the 250 ms player snapshot cadence for diagnostics, buffering, media metadata, watch progress and the rest of the UI.
- Keep frame-driven state local to the timeline components so the entire player page is not invalidated at display refresh rate.
- Stop interpolation while paused, while the user is interacting with the timeline, for unknown/zero duration, or when playback speed is invalid.
- Respect playback speed and clamp interpolation to known duration.
- Add deterministic common tests for the interpolation contract.

## Non-goals

- Do not replace ExoPlayer, create an ExoKit clone or add a multi-player pool.
- Do not create speculative players or preload all provider streams.
- Do not introduce `DefaultPreloadManager` until Nuvio has a source-known next-item use case and can provide a single shared builder/looper for its custom media-source factory, renderers, track selector and load control.
- Do not reuse a stateful `MediaSource` across players or loopers.
- Do not change buffering thresholds, retry policy, decoder priority, renderer order, surface ownership, libmpv, iOS playback or provider/source selection.
- Do not upgrade Media3 or alter the custom AAR files in this change.
- Do not claim measured startup, dropped-frame or rebuffering gains without device evidence.

## Approaches considered

1. **Copy Reddit's feed-first player pool and READY-player prewarming.** Rejected. Reddit's feed presents multiple candidate videos and benefits from preserving several decoders. Nuvio normally has one active content video, and its custom audio/video renderers, libass, FFmpeg, MPV fallback and hardware-decoder limits make a generic pool a new resource-exhaustion risk.
2. **Introduce Media3 DefaultPreloadManager now.** Deferred. Media3 requires the preloader and playback player to share stateful MediaSources, playback looper and core components. Nuvio's custom MediaSource.Factory, RenderersFactory, TrackSelector and LoadControl require a deliberate shared-builder integration; there is not yet a verified, source-known next item to preload.
3. **Move only seekbar interpolation to the Compose frame clock.** Selected. It directly addresses the visible 4 Hz timeline limitation, keeps the existing player engine and snapshot contract intact, introduces no dependency, and confines frame-rate state to the timeline components that draw the progress UI.

## Implementation decisions

- Add pure `shouldAnimatePlaybackTimeline(isPlaying, isInteracting, durationMs, playbackSpeed)` and `interpolatePlaybackPositionMs(positionMs, elapsedNanos, playbackSpeed, durationMs)` helpers in `PlayerTimeline.kt`.
- Add a Compose helper that uses `produceState` and `withFrameNanos` only while playback is active and the timeline is not being dragged/pressed.
- Use the same helper from the standard `PlayerTimeline` and legacy `PlayerSeekBar`.
- Pass the current snapshot's playback speed and playing state into the legacy seekbar; do not infer them from a ticker or global singleton.
- Keep scrubbing, tap-to-seek, seek preview and callback semantics anchored to the active displayed position. Do not change player seek timing or call `seekTo` on frame ticks.
- No new dependency: Compose Runtime is already a declared project dependency (Compose Multiplatform 1.13.0-beta01).
- Keep the project Media3 baseline at 1.8.0. `PlayerTransferState` is a post-1.8.0 API and is not added. Media3 1.11.1 is the latest stable release identified in the AndroidX release notes; 1.12.0-rc01 is a release candidate dated 25 November 2026. Neither can safely replace the custom AAR family without a fingerprinted, source-backed rebuild and API/device validation.

## Verification boundary

- Add common regression tests for speed scaling, fractional speed, invalid speed, negative elapsed time, duration clamping, unknown duration and overflow.
- Re-fetch and statically inspect all changed call sites, signatures, imports, tests and dependency declarations.
- Do not run Gradle, tests, compilation, builds, CI, benchmarks, APK/AAB generation or device playback. These remain outstanding until separately authorized.


## Implementation record — 25 November 2026

- Implemented the pure interpolation function and animation-eligibility policy in PlayerTimeline.kt.
- Implemented a frame-clock state producer with Compose produceState/withFrameNanos. It is used in the main PlayerTimeline and legacy PlayerSeekBar.
- Passed playback state and speed from ProgressControls into the legacy path. The player snapshot polling contract remains unchanged at 250 ms.
- Added common tests for normal/fractional speeds, paused and interacting gates, unknown and known duration, invalid speed/elapsed time, negative position and overflow saturation.
- Re-fetched the modified sources, test, dependency catalog and custom-AAR compatibility guard from the remote branch. Static inspection caught and corrected a local declaration-order issue in the legacy seekbar before completion.
- The new common tests have not been run and no compile result is claimed. No Gradle, build, CI, benchmark or device run was performed.
- No Media3 dependency or AAR was changed. The custom 1.8.0 AAR source/patch provenance remains unverified; Media3 modernization and Android 17 Eclipsa playback remain a separate, source-backed AAR rebuild and validation project.


## API references

- Compose Runtime 1.13.0-beta01 release: https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.13.0-beta01
- Official Compose Runtime MonotonicFrameClock API: https://developer.android.com/reference/kotlin/androidx/compose/runtime/MonotonicFrameClock
- Official Compose Runtime produceState API (producer cancellation on key change/composition removal): https://developer.android.com/reference/kotlin/androidx/compose/runtime/produceState.composable
- AndroidX Media3 releases and compatibility notes: https://developer.android.com/jetpack/androidx/releases/media3
- Reddit engineering rewrite reference: https://www.reddit.com/r/RedditEng/comments/1urq7f6/how_we_rewrote_reddits_video_player_on_android/
