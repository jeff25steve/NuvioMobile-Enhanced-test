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
