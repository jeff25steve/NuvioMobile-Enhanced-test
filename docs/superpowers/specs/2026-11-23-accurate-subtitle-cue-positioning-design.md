# Accurate Fractional Subtitle Cue Rendering

## Intent

Fix the Android Media3 custom subtitle overlay so horizontal text cues retain their source-defined position, anchors, width and text alignment. This prevents WebVTT and other positioned captions from being collapsed to coarse left/centre/right and top/middle/bottom buckets.

## Current behavior and evidence

`SubtitleCuePresenter` currently reduces `Cue.position` and `Cue.line` to three-position enums. It discards `positionAnchor`, `lineAnchor`, `size` and `textAlignment`. `StyledSubtitleOverlay` then positions the whole block at one of nine coarse alignments. The result cannot represent arbitrary fractional cue coordinates or the cue box's intended width.

Media3 1.11.1 documents `position` as the fractional location of `positionAnchor`, `line` as the location of `lineAnchor` for fractional lines, and `size` as the cue-box width for horizontal text. AndroidX Media maintainer discussion in ExoPlayer issue #7243 specifically explains that unset WebVTT size defaults to 100% and that position, anchor, size and text alignment must be considered together to place cues correctly.

## Goals

- Preserve finite horizontal `Cue.position` values and `positionAnchor` values.
- Preserve finite fractional `Cue.line` values and `lineAnchor` values.
- Preserve explicit `Cue.size` and compute a viewport-bounded default width when size is unset but position is explicit.
- Preserve `Cue.textAlignment` independently from the cue-box position.
- Render each distinct placement independently; do not merge cues with different position metadata.
- Keep default-position cues at the current bottom-centre location.
- Keep bitmap cues and unsupported vertical-writing / numbered-line cues on Media3's native subtitle renderer rather than rendering them inaccurately.
- Preserve MPV ASS/SSA parsing behavior and its nine-position alignment mapping.

## Non-goals

- Implement all WebVTT CSS, ruby, text-combine, vertical writing, shear, or arbitrary window styling beyond the supported cue window color.
- Change MPV's native libass subtitle rendering.
- Change subtitle track selection, timing, synchronization or playback engine selection.
- Change subtitle appearance preferences or add settings.
- Change the ExoPlayer SurfaceView or MPV surface lifecycle.

## Approaches considered

1. **Keep the coarse buckets.** Lowest risk but preserves the known placement bug; rejected.
2. **Use a custom Compose layout driven by Cue metadata.** Retains the existing shared styled overlay and renders the source's actual coordinates, anchors, width and text alignment. Unsupported cue classes remain native. Chosen because it fixes the observed defect without replacing the subtitle pipeline.
3. **Route all positioned cues back to Media3's native SubtitleView.** Accurate placement but loses Nuvio's custom font, outline, shadow and color styling for those cues; rejected.

## Design decisions

- Extend `StyledSubtitleBlock` with optional normalized horizontal/vertical fractions, anchor fractions, width fraction and independent text alignment. Existing MPV ASS-created blocks keep their current enum defaults.
- For horizontal cues, use `position` with `positionAnchor`. Media3's `SubtitlePainter` treats an unset position anchor as START; preserve that default. Missing `textAlignment` defaults to CENTER.
- Use `line` and `lineAnchor` only when `lineType == Cue.LINE_TYPE_FRACTION`. Unset line keeps the current bottom default. Numbered lines are passed through natively.
- For explicit `size`, clamp the width to the available video-frame region around the anchor and fill that cue box. If size is unset, measure the text to its content width while limiting its wrapping width to the maximum region available around the position anchor.
- Preserve `Cue.windowColor` only when `windowColorSet` is true; it overrides the user background for that cue. Otherwise retain the user's configured background.
- Resolve `Cue.textSize` from Media3's absolute-pixel, fractional and fractional-ignore-padding modes. The full-bleed overlay uses the fitted video height for both fractional inputs; convert resolved px through Compose Density to sp.
- Extend `SubtitleVideoBoundsPx` with left/top offsets to mirror Media3's `AspectRatioFrameLayout`. Fit returns positive letterbox offsets; Zoom returns negative crop offsets; Fill remains the container bounds.
- Position each block with a Compose `Layout` measure policy relative to the fitted video-frame origin, then clip Zoom overflow to the player container. This keeps the behavior commonMain and avoids platform-specific coordinate code.
- Keep unsupported vertical writing cues and bitmap cues in the native output list.
- Clamp normalized coordinates to [0, 1], reject non-finite values, and keep fallback placement for unset fields.

## Constraints

- Target branch: `main-build-testing`.
- Media3 version: 1.11.1; use its existing Cue API only.
- Compose implementation stays in the existing commonMain source set and uses already-present Compose UI APIs; no dependency upgrades.
- No builds, Gradle tasks, tests, CI, APK/AAB generation, benchmarks or device playback without explicit authorization.
- Add regression tests but leave them unexecuted under the current no-build instruction; do not claim runtime or test success.
- Update the existing engineering notes; do not create duplicate engineering notes.
