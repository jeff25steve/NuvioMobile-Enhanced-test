# Reddit ExoKit and Android Eclipsa Video review

Date: 2026-11-25
Target: `main-build-testing`

## Sources

- [Reddit Engineering: How we rewrote Reddit's video player on Android](https://www.reddit.com/r/RedditEng/comments/1urq7f6/how_we_rewrote_reddits_video_player_on_android/)
- [Reddit follow-up: Taking ExoPlayer Further — Reddit's performance techniques](https://proandroiddev.com/taking-exoplayer-further-reddits-performance-techniques-f426fdafb471)
- [Android Developers: Integrate Eclipsa video](https://developer.android.com/media/platform/integrate-eclipsa-video)
- [Android Developers: Eclipsa Video overview](https://developer.android.com/blog/posts/eclipsa-video-hdr-that-looks-right-on-every-screen)
- [Media3 1.11.1 release notes](https://github.com/androidx/media/blob/1.11.1/RELEASENOTES.md)
- [AOSP: Headroom Adaptive Gain Curves](https://source.android.com/docs/core/display/hagc)

## Reddit architecture lessons

The useful idea is not to copy Reddit's product architecture verbatim. It is to make player ownership and lifecycle policy explicit and centralized.

1. **Central playback ownership.** Keep one authoritative coordinator/store for player identity, active media identity, lifecycle, and transitions. UI surfaces should attach to that owner rather than independently creating and releasing players.
2. **Reuse only when media identity is unchanged.** Retaining a READY player can avoid decoder reinitialization during navigation or temporary surface changes. Pause rather than stop when the same playback is being resumed. When assigning a player to different media, stop/reset it before reassignment so stale frames, decoder state, or media callbacks cannot leak across items.
3. **Never share a MediaSource instance across different player instances.** Build a fresh MediaSource for each player assignment. Treat MediaSource and source-factory state as player/playback-owned, not as a reusable global cache.
4. **Budget decoder resources.** Hardware decoder capacity is finite and varies by device. Keep the number of prepared/active video players bounded; serialize or prioritize playback where concurrent decoding would exhaust device resources. A decoder initialization failure and a first-frame decode failure are distinct recovery signals.
5. **Make preloading conditional and measured.** Prefetch only content that is likely to be viewed next. Broad eager prefetch can improve video start time while degrading unrelated HTTP requests. Do not preload every stream or every source by default.
6. **Restore state without unnecessary player churn.** Preserve media identity, position, track selection, speed, and playback intent across a same-item surface transition. Avoid pause/save/seek/reprepare cycles when the player itself can remain alive.
7. **Instrument the complete playback journey.** Correlate play intent, prepare, first frame, buffering/rebuffering, decoder initialization, errors, retries, surface attach/detach, and user exit. Use monotonic timestamps and bounded, redacted event fields.
8. **Drive seek UI from frame cadence.** A frame-synchronised progress update avoids unnecessary UI-thread work and keeps the scrubber aligned with rendered frames.

Reddit's published percentages are results from its own traffic, devices, and experiments; they are not expected Nuvio gains. The follow-up article reports that aggressive prefetching improved video-start metrics but raised latency for parallel HTTP requests by 2.5%, which is why its authors retained lazy prefetching at that stage.

## Applicability to Nuvio

Nuvio is primarily a single active full-screen playback flow with ExoPlayer and libmpv, not Reddit's scrolling multi-video feed. Therefore:

- Adopt explicit ownership, media identity, per-player source creation, decoder budgeting, state restoration, and event correlation as design invariants.
- Keep the existing MPV/Exo engine split and the existing custom decoder recovery ladder; do not add NextLib or copy Reddit's internal ExoKit.
- Do not add a multi-player pool or eagerly prepare multiple decoders until a concrete multi-surface/feed use case exists and device memory/decoder limits have been measured.
- Preserve the existing surface teardown barrier and generation checks. A pooled player must not outlive or reattach to a stale surface.
- Use a bounded, relevance-driven preload policy; no global prefetch of every candidate stream.
- Validate every change against phone devices and low-memory/decoder-constrained devices, not only emulator tests.

## Eclipsa Video / SMPTE ST 2094-50

Eclipsa Video is based on SMPTE ST 2094-50, also described by AOSP as Headroom Adaptive Gain Curves (HAGC). Android 17 (API 37) adds platform playback/capture support on compatible HDR displays that pass Eclipsa compliance. Media3 release notes for 1.11.0 describe experimental HAGC timed-metadata playback for progressive containers such as MP4 and Matroska: Media3 merges the metadata track with the associated video track and passes it to the decoder on API 37+.

Important compatibility boundary:

- The current version catalog pins Media3 to 1.8.0.
- The repository's prior compatibility review records that the custom Media3 AAR set is aligned to 1.8.0; the attempted 1.12.0-beta01 upgrade was superseded because it was incompatible with those AARs.
- Media3 1.11.1 is a stable upgrade candidate, but Eclipsa/HAGC handling must not be claimed on the current 1.8.0 dependency based only on Android 17's platform support.
- Do not bump the version catalog alone. First rebuild/verify every custom Media3 AAR and extension renderer against one exact stable Media3 version, then test MP4 and Matroska HAGC metadata on API 37+ compliant HDR hardware.
- The Android platform guide says ordinary ExoPlayer playback requires no custom tone-mapping implementation. Do not add a second app-level tone mapper that could conflict with platform HDR composition.
- The upstream Media3 HLS parser fix for `VIDEO-RANGE=PQ/HLG` was merged as [androidx/media PR #3446](https://github.com/androidx/media/pull/3446) on 2026-10-14 and first appears in the 1.12.0-alpha01 line. The current 1.8.0 parser drops this metadata, which is why the host HDR parser tests fail for HLS PQ/HLG variants.
- [PR #3461](https://github.com/androidx/media/pull/3461) separately discusses display-aware HLS variant filtering. Nuvio's custom selector should not be expected to filter HLS variants by HDR capability until the playlist parser supplies reliable `Format.colorInfo`.
- The upstream HLS `VIDEO-RANGE` metadata fix is separate from HAGC timed-metadata playback. Do not conflate HLS PQ/HLG classification with Eclipsa's SMPTE ST 2094-50 dynamic metadata support.

The project already compiles against API 37, but compileSdk alone does not enable Eclipsa playback while the Media3 extractor/player dependency remains at 1.8.0.

## Follow-up implementation order

1. Finish the current host-test regressions before changing the Media3 dependency family.
2. Add playback identity/source ownership assertions around engine rebuilds and source fallback.
3. Audit every MediaSource construction site to ensure a source object is never shared across ExoPlayer instances.
4. Review the existing AnalyticsListener events for stale callback correlation and add missing first-frame/rebuffer/decoder counters without logging signed URLs or headers.
5. Plan a separate Media3 stable upgrade with custom-AAR rebuilds and an API 37 HDR device test matrix.
6. Consider a small bounded player pool only if a measured Nuvio use case needs more than one prepared player.

## Verification boundary

This document records research and implementation constraints. It does not claim Eclipsa playback has been enabled, Media3 has been upgraded, a player pool has been added, or the Reddit performance percentages have been reproduced in Nuvio.
