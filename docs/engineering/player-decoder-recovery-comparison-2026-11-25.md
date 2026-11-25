# ExoPlayer decoder recovery: current project comparison

Date: 2026-11-25
Target branch: `main-build-testing`
Baseline before this cycle: `7ebbc5f60feb64195205572d6bcc68d240e78639`

## External projects reviewed

| Project | Verified recent activity | Relevant implementation | Applicability |
|---|---|---|---|
| [Next Player](https://github.com/anilbeesetti/nextplayer) | Commit `431d34c`, 2026-11-20 | Independent audio/video recovery state, failed-mode sets, last-working video mode, stale retry token rejection, and reader retry before changing decoder | High. Recovery policy ideas transfer; its service/NextLib APIs should not be copied wholesale |
| [NextLib](https://github.com/anilbeesetti/nextlib) | 0.19.0 released 2026-11-18; repository pushed 2026-11-24 | Per-track DecoderManager, active/requested mode distinction, MediaCodec category filtering, player-thread lifecycle contract, native FFmpeg decoder and subtitle integration | High as a reference. GPL-3.0 and native FFmpeg footprint; Nuvio already owns custom software renderers and extraction |
| [moneytoo/Player](https://github.com/moneytoo/Player) | Commit `6df39ae`, 2026-11-12 | Media3/FFmpeg playback; updated to Media3 1.12.0-beta01 | Useful for current upstream adoption, but beta use is not a reason to move Nuvio to a beta |
| [OwnTV](https://github.com/ahXN00/OwnTV) | Commit `6c2b5ce`, 2026-11-22 | libmpv + Media3 with engine-specific routing and fallback ladder | Architecture reference only; Android TV/D-pad-first, so phone UI assumptions do not transfer |
| [CineIsle](https://github.com/nu1lkali/CineIsle) | Commit `fdb17ec`, 2026-11-24 | mpv, GSY/IJK/system and Media3 1.11.1 + NextLib for multi-instance playback | Recent hybrid-engine reference; not enough verified evidence to copy its app-level policy |
| [mpv-android](https://github.com/mpv-android/mpv-android) | Commit `c50a988`, 2026-11-24; active, MIT | Upstream libmpv Android surface attach/detach lifecycle and native bridge | High for native surface integration; its `BaseMPVView` explicitly documents a detach race around VO shutdown, which Nuvio's stronger completion barrier already addresses |
| [VLC Android](https://github.com/videolan/vlc-android) | Commit `8281580`, 2026-11-24; active, GPL-2.0 | Detaches media-player listeners before media replacement/release, releases media separately, detaches video output and bounds debug release waits | Useful lifecycle reference; not a dependency candidate without a deliberate license and architecture review |
| [Mpv-infinity](https://github.com/ZHINFINITY/Mpv-infinity) | Latest commit `1075b4e`, 2026-11-24; changelog says development is paused after 2.5.0 | MPV + Media3 routing and recent buffering/server-switch race fixes | Useful historical comparison only, not treated as an actively maintained source until its hiatus ends |

## Findings applied

Nuvio's ExoPlayer failure handler previously calculated one shared extension-renderer priority for every decoder failure. The custom factory uses that shared value for both video and audio renderers. Consequently an audio renderer failure could also reorder the video renderers, despite no video failure.

The implementation now routes a renderer failure by Media3's actual renderer track type and stores separate audio/video priority overrides. Audio fallback changes only the audio renderer mode; video fallback changes only video priority and retains the existing forced software-video behavior. Unknown renderer types do not trigger either track's fallback. Existing one-fallback-per-configured-priority policy remains intact.

Bluetooth audio remains an explicit audio-only preference: it establishes the configured audio renderer priority, and the audio-specific override can fall back from that preference without changing video ordering.

## Media3 version review

Nuvio remains pinned to Media3 1.8.0. The latest stable release found during this review is 1.11.1 (2026-09-10); 1.12.0-beta01 is also available (2026-11-11). Media3 1.11.1 includes a fix for decoder-error recovery crashes with renderer prewarming (1.10.1), a playback stall fix for prewarmed secondary renderers and an Opus native memory-corruption fix. Media3 1.9.0 added eligible-video seek improvements and better frame dropping; 1.8.0 already includes scrubbing-mode APIs and decode-only seek optimizations.

This is a meaningful upgrade candidate, but it is not included in this change. A separate dependency migration must inspect all Media3 modules, extension renderers, custom subclasses and API removals (including the HAGC ExtractorsFactory migration), then run the authorized build/test matrix before any version bump.

## Native surface lifecycle comparison

The current upstream [mpv-android `BaseMPVView`](https://github.com/mpv-android/mpv-android/blob/master/app/src/main/java/is/xyz/mpv/BaseMPVView.kt) disables VO and calls `detachSurface()` from `surfaceDestroyed`, but its source retains a FIXME that changing `vo` may not wait for VO deinitialization. Nuvio's `MpvSurfaceView.android.kt` already goes further: it observes `MPV_EVENT_VIDEO_RECONFIG`, waits for the old video output to finish uninitializing, then detaches the JNI surface; generation ownership and a lifecycle lock prevent stale surface callbacks from detaching a replacement. Do not replace this with the simpler upstream sample.

VLC Android's `PlayerController` detaches its event listener before replacing/releasing media and detaches video output before releasing the player asynchronously. This is a useful lifecycle invariant to keep checking across Nuvio's MPV/Media3 engine switches, but no additional change was justified in this cycle.

## Deliberately not adopted

- NextLib's DecoderManager is not being added: it overlaps Nuvio's existing custom software renderer and would add native binaries and GPL-3.0 licensing implications.
- Media3 1.12.0-beta01 is not adopted over stable without compatibility and device testing.
- OwnTV's TV-first navigation and device assumptions are not transferred to the mobile app.
- No engine-switch policy, renderer order defaults, decoder preference or retry count was changed beyond isolating the already-existing fallback to the failed track.

## Verification boundary

A host regression test was added before the implementation commit to specify audio-only, video-only, unknown-renderer, non-decoder and already-overridden behavior. Tests/builds were not executed because build and CI execution have not been authorized. No dependency versions changed.


## Additional ecosystem sweep — 25 November 2026

| Project | Verified activity / license | Source reviewed | What transfers / what does not |
|---|---|---|---|
| [Sashimi Android](https://github.com/bitstorm-labs/sashimi-android) | Pushed 2026-11-24; MIT; native phone/tablet Jellyfin client, version 0.12.0 | `PlaybackRecovery.kt`, `PlaybackFailureMapping.kt`, `PlaybackEngine.kt` | Its pure recovery ladder explicitly separates network/stall from decode/source/other and bounds attempts. The direct-play → server-transcode → bitrate-stepdown path depends on Jellyfin server negotiation and does not map to arbitrary Nuvio addon/debrid streams. |
| [Silo Android](https://github.com/Silo-Server/silo-android) | Pushed 2026-11-24; AGPL-3.0; phone + TV | `PlaybackPreflightListener.kt`, `PlaybackCapabilityDetector.kt`, `PlaybackNetworkMonitor.kt`, `PlaybackNetworkRetry.kt`, `Media3SeekRecoveryPolicy.kt`, `PlaybackTimelineSeekPolicy.kt`, `PlaybackTeardownGate.kt` | Strong reference for selected-track preflight against the active output route, connectivity evidence and explicit timeline seek windows. Nuvio already has display-aware HDR selection, track-specific fallback and a custom load-control/cache stack, so duplicating the preflight/network policy is not justified. Code search found the seek classifier definition and tests but no live call site; do not describe it as verified runtime behavior. |
| [Jellyfin Android](https://github.com/jellyfin/jellyfin-android) | Pushed 2026-11-24; GPL-2.0-only; integrated Media3 player inside a web-wrapper client | `PlayerViewModel.kt`, `MediaSourceResolver.kt`, `TrackSelectionHelper.kt`, `DeviceProfileBuilder.kt` | Useful for server-informed playback negotiation, deciding when track changes require a source restart, and codec capability reporting. Its initialization-time hardware/software fallback is not a replacement for Nuvio's custom renderer stack. GPL-2.0-only is not a direct source-reuse candidate for Nuvio's GPL-3.0 project. |
| [Nexio](https://github.com/johnneerdael/nexio) | Archived; GPL-3.0; Android TV / Fire TV | Published technical documentation for progressive VOD cache, parallel range reads and warm-ahead | Documentation is technically detailed, but the repository is archived and TV-first. Nuvio already has selective progressive caching/read-ahead; do not treat this as a maintained baseline or copy its parallel downloader without measurements. |
| [MediaMP](https://github.com/open-ani/mediamp) | Updated 2026-11-22; Apache-2.0 core artifacts | README, backend matrix and builder customization | Useful Compose Multiplatform abstraction reference; Android uses ExoPlayer while MPV is a desktop JVM backend, so it does not offer a better Android dual-engine implementation. |

### Technical comparison notes

- Sashimi's `PlaybackRecoveryPlan` uses a pure policy function with bounded attempts and separate branches: link stalls/network errors retry once and then step down bitrate; decode/source failures prefer server transcode, then limited retries/step-down. This is correct for its server-negotiated Jellyfin source model but would be unsafe to copy as a Nuvio stream policy because arbitrary addon URLs do not expose a common transcoding contract.
- Silo's `PlaybackPreflightListener` evaluates the *selected* track combination after track selection, including Dolby Vision route promises and audio sink/passthrough constraints. Nuvio already has `DisplayAwareDefaultTrackSelector`, Dolby Vision capability logic and audio output routing; the remaining comparison should focus on demonstrable blind spots (e.g. multi-instance decoder constraints), not add a second overlapping listener.
- Silo's `PlaybackNetworkRetry.kt` waits for the network evidence flow and applies 1/2/4/8/15-second backoff. It is a retry helper in a session-oriented client, not proof that Media3's active stream loader should retry forever. Nuvio's current HTTP client already enables OkHttp connection retry and the player separately classifies stream failures/source fallback.
- Silo's `Media3SeekRecoveryPolicy` classifies malformed/unsupported container or manifest, read-position-out-of-range, behind-live-window, and HTTP 404/410/416 as possible seek-boundary errors while excluding decoder, audio-sink, generic network and timeout failures. Its unit tests cover those boundaries. A repository code search surfaced the policy declaration and tests but no call site, so this is a candidate policy shape, not confirmed live recovery. Nuvio currently reopens once for closed-stream, read-position-out-of-range or stuck-playback failures; it has no explicit recent-seek ownership token for a 404/410/416-specific same-route recovery.
- Nexio's documentation gates parallel range downloading and warm-ahead to progressive HTTP(S), keeps HLS/DASH on native Media3 paths, unlocks startup prefetch only after first frame and pauses background prefetch after rebuffer. Those guardrails align with Nuvio's existing selective progressive path; the archived status and added concurrency/memory cost rule out copying it wholesale.

### Research decisions

- Do not add a second capability-preflight layer before comparing its exact selected-track checks with Nuvio's existing selector and audio route code.
- Do not add indefinite network retries to Media3 loaders; a retry must be bounded, cancellation-safe and aware of source generation and connectivity.
- Do not add a seek-specific reanchor/reopen path until Nuvio records explicit seek intent, distinguishes the seek-generated error from later failures, and proves the same source/attempt still owns recovery.
- Keep the Media3 1.8.0/custom-AAR compatibility baseline. New project use of 1.11.x/1.12 beta does not remove Nuvio's custom AAR rebuild and compatibility gate.
- No external code was copied in this sweep. No dependency, playback source, tests, builds, CI or device runs were changed or started.


## ExoPlayer Compose view and Activity lifecycle audit — 25 November 2026

### Nuvio source inspected

The live `main-build-testing` branch was identical to baseline `0eb5e6d30ebf95d78d78e0e3fa3f531f4bf50f5a` before this audit. The inspected implementation is `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt` (6,314 lines), with the MPV surface owner in `MpvSurfaceView.android.kt` (580 lines). The app is pinned to Media3 1.8.0 and Compose Multiplatform 1.13.0-beta01.

The ExoPlayer surface is a `FrameMonitoringPlayerView` hosted by Compose `AndroidView`:

- `factory` creates the view, attaches the current ExoPlayer, configures the API-34 SurfaceSyncGroup workaround, subtitle overlay and frame-rate controller, and stores the view reference.
- `update` refreshes the frame metadata callback, reattaches the current player, updates controller/resize settings, binds subtitle rendering and synchronizes keep-screen-on state.
- `AndroidView` does not provide `onReset` or `onRelease`. With `onReset = null`, Compose does not opt this AndroidView into view reuse. This is not itself a leak or proof of a surface-lifecycle bug.
- A separate `DisposableEffect(exoPlayer)` removes Player/Analytics listeners, clears `PlayerView.player` only if the view still owns that exact player, cancels subtitle/error jobs and stops sidecar subtitles.
- `FrameMonitoringPlayerView.setPlayer` removes its previous player's `VideoFrameMetadataListener` before installing a new listener that captures the new player instance. That protects frame diagnostics against callbacks attributed to a replacement player.
- `DisposableEffect(exoPlayer, lifecycleOwner)` registers the Activity lifecycle observer and releases the ExoPlayer on composition disposal, after releasing the libass overlay and disabling scrubbing mode.
- `ON_START` restores the latest requested `playWhenReady`. `ON_STOP` pauses playback unless the Activity is in PiP; if the Activity is finishing, playback is paused even if PiP state is reported.

The existing code therefore already has explicit player detachment, listener ownership, PiP-aware pause policy and terminal player release. The missing AndroidView `onRelease` callback is not enough evidence to justify another detach path: the current `DisposableEffect` already detaches the view, and ExoPlayer/PlayerView own the attached surface lifecycle.

### Authoritative Media3 / Compose findings

- Android's [Media3 surface guidance](https://developer.android.com/media/media3/ui/surface) prefers `SurfaceView` for ordinary video where possible (power, frame timing, HDR and secure-output advantages), while noting the known Compose interop caveats for `PlayerView` inside `AndroidView`. Nuvio already opts into Media3's API-34 Compose surface-sync workaround where required.
- The official [Media3 Compose UI guide](https://developer.android.com/media/media3/ui/compose) recommends `media3-ui-compose` for Compose-first UI and exposes `PlayerSurface` as a lower-level surface primitive. It does not replace Nuvio's full `PlayerView` responsibilities (custom subtitle surfaces, libass/AIOStreams integration, frame metadata listener, native controls and frame-rate controller) without a larger renderer/UI integration.
- Context7's current AndroidX Compose reference confirms `AndroidView.onRelease` is called on the UI thread when a View is permanently removed and is intended to free resources owned by that View. `onReset` opts the view into possible reuse; leaving it null disables that reuse path. Neither callback should be added decoratively: any new cleanup must have a resource that is not already released by the existing player/view ownership path.
- AndroidX Media3 issue [#3011](https://github.com/androidx/media/issues/3011) reported a TextureView output-surface exception on older hardware/API 28 using Media3 1.9.0. The issue was closed by the stale bot for lack of reproduction information. The Media3 maintainer explicitly clarified that ExoPlayer owns the surface after `setVideoSurface` and listens for TextureView lifecycle events to disconnect it. The issue author's proposed synchronous-clear requirement is therefore not an established Media3 defect or a reason to add an unverified workaround to Nuvio.

### External app comparison and decision

The new mobile-focused [NekoVideo](https://github.com/FellipitoPV/NekoVideo) project advertises Media3/Compose, PiP and a `MediaSessionService` for background playback. Its README is useful as a feature/architecture lead, but this pass did not verify its actual player source or lifecycle implementation; it is not treated as implementation evidence. The previously inspected Silo and Jellyfin clients provide more detailed source-level examples, but their selected-track preflight and server-negotiated source paths do not reveal a missing surface detach invariant in Nuvio.

### Remaining measurable question — non-PiP background resource retention

Nuvio pauses the Activity-owned ExoPlayer on `ON_STOP` when not in PiP, but it does not release the player in that lifecycle callback; release occurs when the owning Compose effect is disposed. This may retain decoder/player resources while the Activity is stopped if the player composition remains alive. It may also be intentional to preserve the active session for a quick return. The source alone cannot establish how long the composition remains retained across the app's real navigation/task transitions, nor whether the retained player materially harms memory/decoder availability or return-to-playback latency.

Do not move release to `ON_STOP` or recreate the player on `ON_START` without measuring the current product lifecycle. The next valid check is an authorized device trace across Home/app-switch, PiP entry/exit, player-route navigation and Activity finish, recording composition disposal, player release, codec allocation and resume latency. Keep PiP as a distinct case. No automatic background service or audio-background behavior should be inferred from this Activity-owned player path.

### Outcome

No confirmed surface ownership defect was found, so no speculative `onRelease`, surface-clear, engine-switch or lifecycle rewrite was applied. The open item is now narrowed from a general surface-lifecycle audit to a measurable non-PiP background resource-retention question. No source code, dependencies, tests, builds, CI or device playback were changed or started.


## sukerxi Jellyfin Android with MPV — 25 November 2026

Repository: [sukerxi/jellyfin-android-with-mpv](https://github.com/sukerxi/jellyfin-android-with-mpv)  
Reviewed default branch: `main`  
Reviewed HEAD: `886300bc68a2fd2e14466d020c8568491d00821c` (20 November 2026)  
Latest release observed: [3.0.0-68](https://github.com/sukerxi/jellyfin-android-with-mpv/releases/tag/3.0.0-68), published 20 November 2026  
License declared by repository: **GPL-2.0-only** (`LICENSE.md`; GitHub license metadata reports GPL-2.0). This is not a source-reuse-compatible license for Nuvio's GPL-3.0-only project absent additional permission or a separate legal basis. Treat this repository as a technical reference; do not copy its source files.

### Project architecture and version baseline

This is a Jellyfin Android fork, not a generic streaming client. It keeps Jellyfin's WebView/client integration and adds an MPV backend alongside ExoPlayer. The README describes a single Activity, Navigation Compose and a persistent WebView host, with playback as a full-screen overlay so returning from playback preserves the underlying page.

The inspected `gradle/libs.versions.toml` pins:

- Android Gradle Plugin 9.4.0, Kotlin 2.4.20, KSP 2.3.12, compile SDK 37, min SDK 26, target SDK 36.
- Compose BOM 2026.09.00, Lifecycle 2.11.0, Coroutines 1.11.0.
- Media3 1.11.1 and Jellyfin's Media3 FFmpeg decoder 1.9.0+1.
- Jellyfin SDK 1.8.12 and `dev.jdtech.mpv:libmpv:1.1.0`; the version-catalog comment identifies that libmpv-android release as mpv 0.42.0 + FFmpeg 9.0.2.

These versions are useful for ecosystem awareness, not a drop-in upgrade set for Nuvio. Nuvio remains pinned to Media3 1.8.0 with its own custom AAR/renderer compatibility constraints. The fork's MPV artifact, SDK, Gradle/AGP and Compose graph need independent provenance and compatibility work before any dependency is considered.

### Source-level findings

#### 1. MPV IPC is serialized off the UI thread

Files: `player/mpv/MpvCore.kt`, `player/mpv/MpvPlayer.kt`.

- All native initialization, property writes, commands and surface attach/detach calls are queued on a dedicated single-thread `mpv-ipc` executor. Native initialization (asset installation, DataStore preference read, config write, MPVLib creation and `init()`) is queued first and runs off the caller/UI thread.
- Hot properties needed by Media3's synchronous `getState()` path—pause, speed, time position, demuxer cache, duration and track-list JSON—are observed and cached. The player reads the cache instead of synchronously calling `mpv_get_property` while mpv may hold its core/dispatch lock during demuxer open, cache fill or decoder initialization.
- Property writes update the hot cache synchronously before their native operation is queued, so immediate state reads see the intended value without blocking for the native safe point.
- The code explicitly documents the trade-off: one ordered queue can delay a surface-size update behind a long `loadfile` or `seek`; splitting native operations across threads would risk violating command ordering. The chosen follow-up is coalescing redundant size updates, not weakening serialization.

This is a strong concurrency/performance pattern. Nuvio's own MPV surface runtime already has a dedicated coroutine dispatcher, a lifecycle mutex and generation fencing; compare the actual Nuvio native property-read path before proposing a second IPC queue or duplicating an existing cache. Never introduce synchronous native MPV property reads into Compose/UI callbacks.

#### 2. Surface replacement and resize churn

File: `player/mpv/MpvPlayer.kt`.

- The MPV-backed Media3 player receives `clearVideoSurfaceView` immediately before `setVideoSurfaceView` on repeated PlayerView binding. The fork reports that treating every clear literally detached and recreated the native output during frequent Compose position recompositions, causing black video with continuing audio.
- It defers a clear by one main-loop turn and cancels that pending detach when the matching set arrives. It also avoids reattaching an already attached identical Surface and only detaches when the view/surface identity still matches.
- Surface dimensions are coalesced until stable for 32 ms. Duplicate dimensions are dropped; the first size after a fresh attach is applied immediately. The code explains that changing `android-surface-size` rebuilds mpv's VO/EGL/swapchain and can interrupt frame output, so applying every intermediate rotation/inset size causes repeated video-output rebuilds.

Nuvio uses a different, native `MpvSurfaceView` owner rather than this fork's Media3 `PlayerView` bridge. Nuvio already has stronger generation ownership, a surface lifecycle mutex, old-output teardown barriers and texture ownership tracking. Therefore do **not** transplant the one-loop deferred-clear workaround. One concrete comparison remains: Nuvio currently sends `android-surface-size` from each accepted `surfaceChanged` / texture-size callback without the same 32 ms settle window. The fork's rationale makes this a plausible rotation-stutter optimization candidate, but not a proven Nuvio defect. Only consider a Nuvio-native coalescer after a device trace shows repeated VO rebuilds or visible rotation stutter; preserve immediate initial sizing, generation checks and final-size correctness.

#### 3. MPV playback-stall watchdog — strongest new reliability reference

Files: `player/mpv/PlaybackStallWatchdog.kt`, `player/mpv/MpvPlayer.kt`, test: `app/src/test/java/org/jellyfin/mobile/player/mpv/PlaybackStallWatchdogTest.kt`.

The author investigated a real Jellyfin HLS outage where mpv repeatedly failed to open segments but remained alive, did not reliably set `paused-for-cache`, continued producing audio underruns, and advanced `time-pos` by a few milliseconds per second. Neither `isBuffering` nor a strict zero-position-change check was sufficient.

The watchdog is a small engine-neutral policy object that receives `positionMs`, playback speed, a scheduler and an `onStall` callback. It uses one delayed Handler task on the player's looper (no extra thread):

- Sample interval: 3 seconds.
- Expected movement per sample: interval × playback speed × 0.15.
- Startup warm-up: five healthy intervals must establish normal playback before stall checks are armed. A single slow interval steps the warm-up counter back one rather than resetting it completely.
- Failure threshold: five consecutive under-progress samples after establishment (roughly 15 seconds).
- It is gated by file loaded, first frame rendered, READY/BUFFERING, not user-paused, not EOF, and no already-pending error.
- A seek resets the sample baseline but preserves the established-playback conclusion; pause/stop cancels scheduled work; new item resets the entire state; one stall is reported once.
- On detection, `MpvPlayer` maps it to Media3 `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT`, marks the current file terminal and queues mpv `stop` before exposing the error. Stopping first matters because the old MPV file otherwise continues its periodic HLS segment retries while the fallback starts.

The committed test uses a virtual scheduler and has 15 cases covering no-progress startup, intermittent cold start, healthy playback, slow position crawl, transient missed intervals, pause/resume, seek re-baselining, one-shot reporting, new-item reset and speed-aware progress. The 16 November commit message reports these tests passed and describes an API 34 x86_64 emulator test with a Jellyfin 720p HLS transcode, including server termination and resume from the saved position. Those are the author's recorded results; this review did not run that repository's tests or reproduce the experiment.

**Transfer boundary:** this is a useful second signal to compare with Nuvio's first-frame and frame-output-gap watchdogs. It measures media-clock progress after first output, whereas Nuvio's frame-metadata watchdog measures a frame-output pipeline gap while media position advances. Those signals are complementary, not interchangeable. Before implementation, locate the current Nuvio MPV position/cache observables and confirm whether the MPV path already has a same-purpose watchdog. If it does not, first add deterministic diagnostic policy tests and prove the signal on Nuvio's actual stream types/devices. Do not import the 3 s / 15% / 5-cycle thresholds: they were tuned for one Jellyfin HLS 720p emulator scenario, not arbitrary addon, debrid, local, DASH or live streams. The Jellyfin fallback below is not portable to Nuvio.

#### 4. First-frame-driven seek HUD timing

Files: `player/PlayerViewModel.kt`, `ui/screens/player/PlayerGestureHandler.kt`.

The fork adds a persistent `StateFlow<Long>` resume tick. A seek gesture snapshots the tick before issuing `seekTo`, then suspends until the tick changes; `StateFlow.first { it != oldTick }` avoids a polling loop and handles the already-changed-before-collector-starts case. The MPV event `PLAYBACK_RESTART` and ExoPlayer `onRenderedFirstFrame` both increment the same flow.

The fork explicitly rejected ExoPlayer `onPositionDiscontinuity(SEEK)`: its `seekTo()` call dispatches that callback synchronously before the new frame is rendered. Using it hid the seek HUD roughly 20 ms after release and exposed the frozen interval before the picture returned. In the reported emulator test, MPV's playback-restart signal dismissed the HUD 41 ms after seek while playing and 24 ms while paused; ExoPlayer's first frame arrived 475 ms after seek and the HUD cleared 21 ms later (one Compose frame). The commit also coordinates the seek card with the loading indicator to avoid a large spinner flashing immediately after the card disappears.

This is a high-value UX/state-machine reference. The semantic rule is transferable: a seek-complete visual affordance should wait for actual rendered output, not a seek-command acknowledgement or position discontinuity. Preserve source/player generation fencing in Nuvio so an old player's delayed first-frame event cannot complete a newer seek. Nuvio's existing first-frame/frame-output instrumentation provides useful primitives, but the seek HUD behavior itself needs a focused comparison against current Nuvio UI before any implementation.

#### 5. Error fallback is Jellyfin-server-specific

Files: `player/PlayerViewModel.kt`, `player/queue/QueueManager.kt`, `player/source/MediaSourceResolver.kt`.

The queue manager has a bounded, source-negotiated fallback ladder: first retry with server defaults, then disable direct play (permit direct stream), then disable direct stream (force server transcode), with at most three attempts. If the current source is already transcoding, it only retries once for a transient failure. Retry count resets after 30 seconds of stability or a new session. The player reports network timeout/connection loss as retryable to the UI and captures the current position before asynchronous fallback. This is robust in a Jellyfin client because the server can generate a new media source and transcode URL.

Nuvio's arbitrary addon/debrid sources do not expose this common Jellyfin `PlaybackInfo` negotiation contract. Do not copy the direct-play/direct-stream/transcode ladder or assume a source can be downgraded by changing a local flag. Only the general properties transfer: bounded retry budget, preserve resume position, stop the failed engine before retry, reset attempts on new source or proven stability, and keep retry ownership tied to the current source generation.

#### 6. Additional useful patterns

- The fork has a debug-only gesture harness that runs a real ExoPlayer and the production gesture overlay without needing a live Jellyfin server. This is a good testing approach for isolating player UI/gesture semantics from the provider/server path.
- MPV track selection and ASS/SSA handling are deliberately separate from Media3's generic track-selection structures. Its device profile advertises MPV-specific embedded/external subtitle formats; its README claims native ASS styling via MPV/libass. This is Jellyfin profile negotiation and does not replace Nuvio's own subtitle pipeline.
- The same-frame comparison feature uses independent MPV instances to render a captured frame under alternate configurations and compare pixel output. It is a useful diagnostic concept but has a meaningful native-memory/GPU cost; do not add it to production playback without a dedicated debug-only resource budget.
- The README's mobile-data prompt and unified buffer tier are backed by server-specific bitrate negotiation and paired MPV `cache-secs` + `demuxer-max-bytes` limits. Nuvio already owns its own cache/read-ahead policy; do not copy the settings or limits without benchmarking its own sources.

### Adopt / defer / reject decisions for Nuvio

| Finding | Decision | Reason |
|---|---|---|
| Dedicated serialized MPV IPC + hot-property cache | Compare only | Nuvio already has a single-thread MPV dispatcher, lifecycle mutex and generation fencing; verify UI property reads before changing architecture |
| One-loop deferred surface clear for PlayerView | Reject for current Nuvio surface owner | Different bridge; Nuvio has native surface generation ownership and a teardown barrier |
| 32 ms surface-size settle/coalescing | Benchmark candidate | Nuvio currently forwards accepted size callbacks immediately; possible rotation VO churn, not yet measured |
| Progress-based MPV stall watchdog | High-priority comparison candidate | Adds post-first-frame media-clock stall detection complementary to frame-output gap detection; requires Nuvio-specific thresholds and source gates |
| First-rendered-frame seek HUD | High-value UX comparison candidate | Better semantic signal than seek-discontinuity acknowledgement; requires checking current Nuvio seek UI and source fencing |
| Jellyfin direct-play → direct-stream → transcode ladder | Reject as direct implementation | Requires Jellyfin PlaybackInfo/server transcode negotiation absent from generic Nuvio addon sources |
| MPV/Media3 dependency versions | Do not transplant | Different AAR/native graph and GPL-2.0-only source baseline; Nuvio remains on its own compatibility baseline |

No source code, dependency, test, build, CI or device run was changed or started while reviewing this project.

### Nuvio MPV progress-stall diagnostic — 25 November 2026

Implemented an independent, Nuvio-native progress watchdog based on the failure mode observed in the Jellyfin MPV fork:

- Added `MpvPlaybackProgressWatchdog` with monotonic sampling, playback-speed-scaled expected progress, five healthy warm-up intervals, and five consecutive under-progress samples before a one-shot report. The policy does not rely on `paused-for-cache`; the caller gates on current playback restart, user pause, seek state and EOF.
- Integrated a 3-second polling coroutine into the existing `LibmpvPlayerSurface` observer lifecycle. It uses the existing `view.snapshot()` path (therefore the existing MPV dispatcher/property snapshot cache), `SystemClock.elapsedRealtime()`, and the composition-owned coroutine scope. Disposal cancels the poll; START_FILE resets the policy; PLAYBACK_RESTART re-baselines it after load/seek.
- Reports are diagnostic-only. No automatic engine switch, MPV stop, timeout injection or source retry is performed. This avoids promoting a heuristic into a destructive recovery action before Nuvio has device/HLS measurements.
- Added host tests for warm-up gating, one-shot reporting, speed scaling, ineligible-period re-baselining and stale-attempt rejection.

The initial 15% expected-progress threshold and sample cadence are a conservative diagnostic starting point informed by the external project's documented failure mode, not a Nuvio-measured production threshold. The policy must be tuned only from captured playback traces. The external GPL-2.0-only source was not copied.

**Verification boundary:** source and test files were committed to `main-build-testing`; tests were authored but not executed. No build, CI or device run was started. Context7's exact 1.10.2 coroutine docs were unavailable; the closest indexed coroutine documentation confirms structured cancellation for child jobs and suspending `delay`, while Nuvio already uses these APIs and has the dependency pinned at 1.10.2. No dependency change was required.

### Follow-up audit — watchdog polling cost and terminal lifecycle

A source review found the initial polling loop still requested a native-backed MPV snapshot every three seconds while the player was paused or idle, even though the watchdog could not report in those states. The loop now re-baselines and skips the snapshot call until a playback-restart boundary is active and the player is neither user-paused nor seeking. END_FILE clears the active playback gate, preventing post-EOF polling. Buffer-cache stalls remain observable because `paused-for-cache` is deliberately not used as an exclusion. The playback-speed regression test now asserts the five-sample threshold at 2x speed.

This is a real efficiency improvement: it removes unnecessary MPV dispatcher/property reads during idle, pause, seek and after END_FILE without changing playback behavior. The source/test changes remain unexecuted due to the no-build/no-test restriction.


## Source-correctness audit from the six-item review — 25 November 2026

The six proposed items were checked against the current Nuvio player source, Media3 1.8.0 APIs, and the cited upstream issue/PR records. Only the format-boundary diagnostic was a code change in this pass.

### 1. Dolby Vision under-report — device validation remains required

The code already has Dolby Vision-aware format selection and renderer handling. The reported remaining uncertainty is whether a device/stream that under-reports Dolby Vision capability still outputs Dolby Vision rather than HDR10. This cannot be proven by another source-level heuristic. Validate on an actual DV-capable display with a known Profile 8 PQ sample, compare the device/display OSD against the source metadata, and capture the selected decoder. Do not claim DV output correctness from MIME/codec selection alone.

### 2. HDR/DV input format diagnostics — implemented

AnalyticsListener.onVideoInputFormatChanged(EventTime, Format, DecoderReuseEvaluation?) now emits one concise videoFormatHdr record per HDR or Dolby Vision format transition. It records attempt, MIME type, codec string, dimensions, frame rate, color space, range, transfer function (PQ/HLG/SDR/unknown), luma/chroma bit depth, presence of static HDR metadata, and the currently known decoder name. It does not log per frame and adds no dependency.

Interpretation boundary: this records the input format signalled to Media3. It does not prove that the display negotiated HDR, that Dolby Vision metadata survived the full pipeline, or that the panel rendered DV rather than an HDR10 fallback. The decoder-initialized event is separately logged by the existing listener; it may occur after the format-change log, so decoder=not_initialized is a valid ordering outcome rather than evidence of missing decoder selection.

### 3. Mid-stream decoder recovery — deliberately deferred

The cited AndroidX Media PR #3422 remains an upstream proposal, not an API available to the pinned Media3 1.8.0 integration. Its related issue #3421 describes the exact gap: setEnableDecoderFallback covers decoder initialization failures, not a decoder that fails after successful initialization. Nuvio already enables initialization-time fallback. Do not add an application-level mid-stream decoder rebuild until the upstream API lands or a reproducible Nuvio-specific failure demonstrates a safe bounded workaround. A local retry can change decoder selection and source position, but also risks repeated failures, duplicate source work and lifecycle races.

### 4. Player release and secure-codec ownership — audit, no teardown change

AndroidX Media issue #3379 documents an Amlogic secure decoder initialization failure with NO_MEMORY; the maintainer attributes it to device/secure-memory limits, concurrent secure decoders, unreleased prior sessions or memory pressure, and recommends correct player.release() when playback finishes or the app goes into the background. Nuvio's current ExoPlayer disposal path already removes listeners, detaches the exact PlayerView-owned player, cancels player-scoped work and calls release(). Engine replacement and leaving the player route dispose the player-owning composition. The Activity stop path pauses outside PiP; it does not unconditionally release while the playback route remains alive. No unconditional background release was added because that would change resume/PiP behavior without evidence that Nuvio retains a completed session or leaks a codec. Device validation should capture release on route exit, engine switch, PiP end and Activity finish, while separately checking intentional background/PiP continuity.

### 5. Long background pause and decoder error 4003 — no generic retry

AndroidX Media issue #3148 is open and its maintainer discussion is more specific than the initial summary: stopping a foreground media service signals that resources may be reclaimed; Media3's recommended paused-audio behavior is to keep the foreground service active for a further ten minutes (or until a permanent playback-ending signal), and Media3 session handling implements that policy. The maintainer also notes that the reported non-recoverable codec error does not currently cause ExoPlayer to retry automatically, with the codec-framework investigation still unresolved in the latest issue discussion reviewed. Nuvio's current player is Activity/composition-owned and does not implement the same persistent background audio service contract. Therefore do not add a broad prepare() retry for every 4003/decoder error or claim this issue proves a Nuvio defect. First capture a Nuvio-specific error with lifecycle state, error cause, codec name and player ownership; only then consider a single generation-fenced retry for the precise reproduced case. Media3 documents prepare() as a retry path for failed/stopped playback, but that alone does not prove every codec-reclamation failure will recover.

### 6. Custom Media3 AAR upgrade — keep pinned

Nuvio's custom renderer/extractor AARs and Media3 API usage form one compatibility unit. No isolated Maven alias bump was made. Upgrade only as a coordinated, provenance-checked rebuild of all custom binaries and matching exclusions, followed by the authorized compatibility/build matrix.

### 7. Frame-rate policy — no change

The current long-content/live-stream preference gate matches the Android guidance already documented in the existing notes. No defect or regression evidence was found.

### Explicitly rejected as unsupported changes

- No profile-5 GPU tone-map addition.
- No broad player architecture rewrite for size alone.
- No restoration of Media3 1.12 beta flags.
- No automatic recovery from every watchdog or codec error.
- No mid-stream decoder recovery before the upstream API or a reproducible Nuvio-specific failure.

**Verification boundary:** the HDR/DV callback uses already-imported Media3 1.8.0 types/constants and adds no dependency. Remote source and imports were re-fetched after the change. No build, compiler, unit test, CI or device validation was run, so compilation and real DV output remain unverified.
