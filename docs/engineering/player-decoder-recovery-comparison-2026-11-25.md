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
