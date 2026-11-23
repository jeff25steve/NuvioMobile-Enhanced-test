# Accurate Media3 Subtitle Cue Rendering

## Intent

Fix the Android Media3 custom subtitle overlay so horizontal text cues retain source-defined geometry and embedded styling. Positioned WebVTT captions must not be reduced to coarse alignment buckets, and subtitle placement must follow the fitted video frame used by Media3's mobile PlayerView.

## Current behavior and evidence

Before this change, `SubtitleCuePresenter` reduced `Cue.position` and `Cue.line` to three-position enums. It discarded `positionAnchor`, `lineAnchor`, `size`, `textAlignment`, `windowColor` and `textSize`. `StyledSubtitleOverlay` positioned blocks against the outer player container and could not preserve Fit letterbox or Zoom crop offsets.

Media3 1.11.1's `Cue.java`, `SubtitlePainter.java`, `CanvasSubtitleOutput.java` and `SubtitleViewUtils.java` define cue geometry and embedded text-size semantics. The official `exo_player_view.xml` places `SubtitleView` inside `exo_content_frame` (`AspectRatioFrameLayout`), whose `onMeasure` applies Fit/Zoom aspect-ratio sizing. That is the reference geometry mirrored here. ExoPlayer issue #7243 discusses why cue position, anchor, size and text alignment must be treated together.

AndroidX Media issue #2977 requests a separate TV-oriented option to place subtitles outside the video bounds and into black bars. Its maintainer explains that subtitle formats commonly position content relative to the video and that the default PlayerView behaves accordingly. This project intentionally keeps the mobile default and does not add the TV-specific black-bar extension.

## Goals

- Preserve finite horizontal `Cue.position` values and `positionAnchor` values.
- Preserve finite fractional `Cue.line` values and `lineAnchor` values.
- Preserve explicit `Cue.size`; when size is unset, measure text to its content width while limiting its wrapping width to the space available around the position anchor.
- Preserve `Cue.textAlignment` independently from cue-box position.
- Preserve `Cue.windowColor` only when `windowColorSet` is true; otherwise retain the user's configured subtitle background.
- Preserve `Cue.textSize` in absolute-pixel, fractional-viewport and fractional-viewport-ignore-padding modes.
- Position cues relative to the fitted video frame, including Fit letterbox and Zoom crop offsets.
- Keep distinct cue geometries/styles separate; do not merge cues that differ in placement, text alignment, window color or text size.
- Keep default-position cues at bottom-centre within the fitted video frame.
- Keep bitmap cues, vertical-writing cues and numbered-line cues on Media3's native renderer.
- Preserve MPV ASS/SSA parsing and its nine-position alignment behavior.

## Non-goals

- Implement all WebVTT CSS, ruby, text-combine, vertical writing, shear or arbitrary window styling beyond the supported cue window color.
- Place mobile subtitles outside the fitted video bounds into black bars; that is a separate TV-oriented feature request.
- Change MPV's native libass subtitle rendering.
- Change subtitle track selection, timing, synchronization or playback engine selection.
- Change subtitle appearance preferences or add settings.
- Change ExoPlayer or MPV surface lifecycle.

## Approaches considered

1. **Keep coarse buckets and container-relative placement.** Lowest risk but preserves the observed rendering defects; rejected.
2. **Use a custom commonMain Compose Layout driven by Media3 Cue metadata and fitted video bounds.** Retains Nuvio's styled overlay, measures each cue, applies source anchors and positions against the same video frame used by Media3 PlayerView. Unsupported cue classes remain native. Chosen.
3. **Route all cues back to Media3's native SubtitleView.** Would reproduce Media3 semantics but loses Nuvio's custom font, outline, shadow and color treatment for styled tracks; rejected.

## Design decisions

- Extend `StyledSubtitleBlock` with optional normalized x/y fractions, anchor fractions, width fraction, independent text alignment, cue window color and cue text-size value/type. Existing MPV ASS blocks keep their enum defaults.
- Media3's `SubtitlePainter` treats `Cue.TYPE_UNSET` for horizontal position and fractional line anchors as START. Keep that exact default; do not derive it from text alignment. Missing `textAlignment` defaults to CENTER.
- Use `line` and `lineAnchor` only for `Cue.LINE_TYPE_FRACTION`. Numbered lines and vertical-writing cues remain native.
- For explicit `Cue.size`, use the requested width bounded by the space around the position anchor. If size is unset, measure text to its content width, but cap its wrapping width to the maximum region available around the position/anchor; do not stretch it to a full-width cue box.
- Preserve `Cue.windowColor` only when `windowColorSet` is true; it overrides the user background for that cue. Otherwise retain the user's configured background.
- Resolve `Cue.textSize` using Media3's absolute-pixel, fractional and fractional-ignore-padding modes. The full-bleed overlay uses the fitted video height for both fractional inputs; convert resolved px through Compose Density to sp.
- Extend `SubtitleVideoBoundsPx` with left/top offsets to mirror Media3's `AspectRatioFrameLayout`. Fit returns positive letterbox offsets; Zoom returns negative crop offsets; Fill remains the container bounds.
- Position each block with a Compose `Layout` measure policy relative to the fitted video-frame origin, then clip Zoom overflow to the player container. Do not extend mobile subtitles outside the video frame.
- Reject non-finite/out-of-range normalized coordinates and keep safe fallback placement.
- Keep bitmap, vertical-writing and numbered-line cues in the native output list rather than misrendering them in the custom horizontal overlay.

## Constraints and verification

- Target branch: `main-build-testing`.
- Media3 version: 1.11.1; use existing Cue APIs only.
- Project Compose Multiplatform dependency is 1.13.0-beta01. Context7 did not return version-matched docs for that beta; stable common Compose Layout/Density APIs were cross-checked against official JetBrains/Compose API sources. No dependency changes were made.
- No Gradle tasks, tests, compilation, builds, CI, APK/AAB generation, benchmarks or device playback without explicit authorization.
- Regression test source is added but intentionally unexecuted under the current no-build instruction. No runtime or test-pass claim is made.
- Detailed implementation notes belong in the existing engineering notes; do not create duplicate engineering notes.
