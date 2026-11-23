# libmpv source and post-filter video fidelity — design

Date: 23 November 2026  
Target: `main-build-testing`

## Intent

Make Android Playback Info accurately distinguish the video decoded from the source from the format produced after mpv video filters. Correct bit-depth detection for common FFmpeg/mpv pixel formats and stop presenting mpv's potentially inaccurate container FPS as the only frame-rate measurement.

## Verified current behavior

1. `mpvBitDepth` in `PlayerMediaInfo.kt` recognizes only pixel-format strings containing `10` or `12`; it misses known 8-, 9-, 14- and 16-bit formats.
2. `buildLibmpvMediaInfoJson` reads source bit depth from `video-params/pixelformat`, but reads `pixelFormat` from `video-out-params/pixelformat`. The UI presents these as unqualified rows, so a source such as 10-bit HEVC filtered to `yuv420p` can appear to be 10-bit output.
3. The same JSON builder uses `container-fps` for `fps`, and the UI labels it simply `Frame Rate`. mpv's manual explicitly warns that container FPS can be bogus and documents `estimated-vf-fps` as a recent-frame estimate of the video-filter output rate.

## Chosen approach

- Expand the pure pixel-format parser to report known 8-, 9-, 10-, 12-, 14- and 16-bit formats, including planar, semi-planar and common packed RGB aliases. Return null for unknown/hardware-only formats rather than guessing.
- Keep the existing `bitDepth` field as source/decoded bit depth for compatibility. Add explicit `sourcePixelFormat`, `outputBitDepth`, `outputColorRange` and `estimatedOutputFps` fields to libmpv's Playback Info JSON. Here, “output” means mpv's post-filter `video-out-params`, not a claim about the final display scan-out.
- Label the existing mpv `fps` value as `Container FPS`; show `estimated-vf-fps` separately as `Estimated Output FPS` when it is finite and positive. Keep ExoPlayer's selected-format frame rate label unchanged.
- Label source and post-filter pixel format, bit depth and color range separately. Omit unavailable values.

## Goals

- Correctly report known pixel-format bit depths from 8 through 16 bits.
- Make source versus post-filter format details unambiguous.
- Expose mpv's estimated post-filter FPS without misrepresenting container FPS.
- Add pure regression coverage for supported/unknown formats and invalid FPS values.

## Non-goals and constraints

- No changes to decoder choice, hardware decoding, shaders, filters, color conversion, HDR selection, frame-rate switching, buffering, playback URLs, iOS or ExoPlayer rendering.
- No new dependencies; keep Media3 at `1.12.0-beta01`.
- Do not claim post-filter metadata proves what the physical display actually presents.
- No Gradle tests, compilation, builds, CI, benchmarks or device playback without explicit authorization.
- Update the canonical engineering notes without duplicating the earlier playback-format-fidelity record.

## Evidence

- mpv property documentation: `video-params` describes decoder output; `video-out-params` describes the format after video filters. `container-fps` may be inaccurate; `estimated-vf-fps` is the estimated filter-chain output FPS averaged over recent frame durations: https://mpv.io/manual/stable/#command-interface-properties
- mpv's filter documentation states that the `format` filter converts to the requested pixel format: https://mpv.io/manual/stable/#video-filters
- FFmpeg/mpv pixel-format naming includes high-bit-depth planar formats (9/10/12/14/16-bit), P010/P016 and packed RGB formats such as RGB48/RGBA64. The parser will explicitly recognize common forms and leave unknown formats unset rather than infer from arbitrary substrings.

## Acceptance

- Tests cover 8/9/10/12/14/16-bit planar/semi-planar/packed formats and unknown formats.
- Tests cover finite positive estimated FPS and omission of null, zero, negative, NaN and infinity.
- Playback Info labels source and post-filter data separately.
- mpv container FPS is no longer presented as an unqualified frame rate.
- No playback behavior or rendering configuration changes.
