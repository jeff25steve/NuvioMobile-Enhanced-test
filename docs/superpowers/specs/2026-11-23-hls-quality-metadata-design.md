# HLS quality metadata visibility design

Date: 23 November 2026  
Target: `main-build-testing`

## Intent

Make manual HLS quality choices more informative by showing the stream's declared dynamic range and frame rate alongside the existing resolution, codec and bandwidth. This helps users distinguish SDR from PQ/HLG variants and compare, for example, 24 fps and 60 fps encodes without guessing from resolution alone.

## Current behavior

`PlayerQualityResolver` parses `#EXT-X-STREAM-INF` attributes into a private `HlsVariant`, then maps them into `PlayerQualityVariant`. The sanitized one-variant playlist preserves the original `streamInfoLine`, so Media3 still receives the original HLS attributes. However, the UI model currently discards `VIDEO-RANGE` and `FRAME-RATE`, and `PlayerQualityPanel` therefore cannot show them.

## Chosen approach

Add optional `videoRange` and `frameRate` metadata to `PlayerQualityVariant`, with defaults so existing construction sites remain source-compatible. Parse `VIDEO-RANGE` case-insensitively into the known HLS values `PQ`, `HLG`, and `SDR`; omit unknown values rather than inventing a meaning. Parse only finite, positive frame rates. Include concise labels in the existing `displayLabel`, which the quality panel already renders as its subtitle:

- `PQ` → `HDR (PQ)` (do not label it HDR10; the HLS value alone does not prove a particular HDR10/HDR10+ mastering format).
- `HLG` → `HDR (HLG)`.
- `SDR` → `SDR`.
- A valid frame rate → a rounded two-decimal `N fps` label with redundant trailing zeroes removed.

Unknown, absent, malformed, zero, negative, NaN and infinite frame-rate values are omitted. Existing quality ordering, Auto behavior, sanitized playlist contents, playback URLs and AV1 warning behavior remain unchanged.

## Approaches considered

1. **Expose manifest metadata in the existing quality model (chosen).** Small common-code change; no Android display APIs or playback-policy changes; useful and honest labels in the existing UI.
2. **Implement custom HDR-aware adaptive selection.** Rejected for this cycle. Media3 upstream PR #3461 describes display-capability-aware ranking, separate adaptive groups and reselection when display HDR capabilities change, but it is not included in 1.12.0-beta01 release notes and remains an upstream review item. Reimplementing hundreds of lines locally would risk device/display regressions.
3. **Only label the UI without retaining parsed metadata.** Not viable; the panel needs structured values from the resolver.

## Goals and non-goals

Goals:
- Preserve and expose the HLS `VIDEO-RANGE` and `FRAME-RATE` values.
- Keep old call sites compatible through optional defaults.
- Add deterministic common tests for normalization, formatting and omission of invalid values.
- Update the existing canonical engineering notes without duplicating the research history.

Non-goals:
- Do not change Media3 adaptive track selection, HDR display capability handling, decoder selection, playback URL selection, quality recommendation, or server transcoding.
- Do not claim that a displayed HDR label guarantees device support, correct tone mapping, or HDR output.
- Do not add dependencies or alter Android/iOS playback engines.

## Constraints

- Preserve current Auto adaptive playback and manual one-variant playlist behavior.
- Keep the change in common Kotlin code so Android and iOS share identical labels.
- Do not run Gradle builds/tests or CI without explicit authorization.
- Record that regression tests are added but unexecuted under the current authorization boundary.

## Evidence

- HLS master playlist attributes are defined by the HLS specification; Media3 1.12.0-beta01 parses PQ/HLG into `Format.colorInfo` (AndroidX Media release notes, 11 November 2026).
- AndroidX Media issue #3441 documents equal-bandwidth PQ/SDR variant selection problems.
- AndroidX Media PR #3461 describes display HDR capability ranking, range-separated adaptive groups and reselection after display changes; it is not listed in the 1.12.0-beta01 release notes.
- A real Jellyfin client report documents an SDR fallback selected instead of a PQ remux on first playback: https://github.com/jellyfin/jellyfin-androidtv/issues/5245

## Acceptance

The chooser shows range and frame rate only when valid metadata exists; existing options without those attributes render exactly as before. Auto and manual playback behavior is unchanged. The test source covers PQ/HLG/SDR normalization, unknown values, valid frame-rate formatting, invalid frame rates and combined display labels. No runtime playback improvement is claimed without authorized tests and device validation.
