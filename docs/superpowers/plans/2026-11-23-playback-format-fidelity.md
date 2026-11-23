# Playback format fidelity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct playback dynamic-range identification, expose actual bit depth/color range in Playback Info, and harden the HLS metadata parser.

**Architecture:** Keep engine-specific metadata extraction in the existing Android player engine and shared mpv helper, but normalize it through small pure functions. The common Playback Info modal renders normalized metadata and the HLS resolver passes validated attributes into the existing quality model. No playback-selection or rendering policy changes.

**Tech Stack:** Kotlin Multiplatform common Kotlin, Android Media3 1.12.0-beta01, Compose Multiplatform, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-23-playback-format-fidelity-design.md`

## Global Constraints

- Keep Media3 at `1.12.0-beta01`; add no dependencies.
- Do not alter adaptive track selection, decoders, frame-rate policy, buffering, surface handling, subtitles or iOS playback.
- Show only metadata that the active engine actually reports.
- No Gradle tests, compilation, builds, CI, benchmarks or device playback without explicit authorization.

## Review Focus

- Dolby Vision codec strings with fallback HEVC/AVC/AV1 MIME types — preserve DV identification and profile.
- BT.2020 primaries with SDR transfer — do not label as HDR.
- Missing/unknown color range or bit depth — omit the row rather than invent a value.
- mpv color levels other than `full` / `limited` — omit normalized range.
- HLS range/frame-rate values with casing, whitespace, malformed numbers and non-finite numbers — normalize or omit safely.
- HLS resolved URI containing dollar-sign sequences — preserve literally during regex replacement.
- Auto HLS quality metadata remains declared metadata, not a claim about the currently adaptive-selected variant.

---

### Task 1: Harden HLS metadata parsing and URI rewriting

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerQualitySelector.kt`
- Create: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/PlayerQualityResolverMetadataTest.kt`

**Interfaces:**
- Produces: `PlayerQualityVariantMetadata(videoRange: String?, frameRate: Double?)`, `parsePlayerQualityVariantMetadata(attributes: Map<String, String>)`, and `rewriteHlsAttribute(line: String, attributeName: String, value: String)`.
- The resolver uses these helpers when mapping `#EXT-X-STREAM-INF` attributes and rewriting `#EXT-X-MEDIA:URI`.

- [x] **Step 1: Write common tests first**
  - Normalize `VIDEO-RANGE` values `pq`, ` HLG `, and `sdr` to canonical values.
  - Omit unknown/missing ranges.
  - Parse finite positive frame rates; omit malformed, zero, negative, NaN and infinite values.
  - Rewrite a quoted URI while preserving literal `$1` and `$token` in the replacement URL.
  - Append a quoted URI attribute when the original line has none.
- [x] **Step 2: Implement the minimal parser helpers**
  - Extract the HLS metadata helper and use it from `HlsMasterPlaylist.parse`.
  - Change URI replacement to a lambda so replacement text is not interpreted as regex group syntax.
  - Preserve the original stream-info line and all existing playback behavior.
- [x] **Step 3: Record source review**
  - Confirm the corrected regex quoting remains identical to the original intended behavior.
  - Do not run Gradle tests without authorization.

### Task 2: Correct dynamic-range metadata for ExoPlayer and libmpv

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerMediaInfo.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt`
- Create: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/PlaybackFormatMetadataTest.kt`

**Interfaces:**
- Produces pure helpers:
  - `exoPlaybackHdrFormat(sampleMimeType: String?, codecs: String?, colorTransfer: Int): String`
  - `exoDolbyVisionProfile(codecs: String?): String`
  - `exoPlaybackColorRange(colorRange: Int): String`
  - `mpvPlaybackHdrFormat(dvProfile: String?, videoCodec: String?, videoDecoder: String?, codecProfile: String?, gamma: String?, colorMatrix: String?): String`
  - `mpvPlaybackColorRange(colorLevels: String?): String?`
- The ExoPlayer JSON builder and libmpv JSON builder consume the normalized results.

- [x] **Step 1: Add Android host tests**
  - Cover DVHE, DVH1, DVAV, DVA1 and DAV1 Dolby Vision codec families with fallback MIME.
  - Cover Dolby Vision MIME, PQ, HLG, SDR and unknown transfer values.
  - Cover DV profile extraction for HEVC, AVC and AV1 codec strings.
  - Cover full/limited/unknown color ranges.
  - Prove BT.2020 matrix/primaries without PQ/HLG/DV signalling is not classified as HDR.
- [x] **Step 2: Implement ExoPlayer classification**
  - Make playback-info DV detection/profile extraction use the same five codec families already recognized by the player media-info helper.
  - Map ST2084 to generic PQ/HDR, HLG to HLG, explicit SDR to SDR; do not infer HDR10+ from a codec string.
- [x] **Step 3: Implement libmpv classification**
  - Detect DV from explicit profile/codec/decoder or `video-params/colormatrix=dolbyvision`.
  - Detect PQ/HLG/SDR from `video-params/gamma`.
  - Remove the BT.2020-primaries-only HDR fallback.
  - Reuse the classification helper in `mpvMediaInfo` so the two info surfaces agree.
- [x] **Step 4: Update playback format tests and source review**
  - Ensure all codec prefixes and transfer cases have assertions.
  - Do not run Gradle tests without authorization.

### Task 3: Expose bit depth, color range and declared HLS metadata in Playback Info

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlaybackInfoModal.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerMediaInfo.kt`

**Interfaces:**
- ExoPlayer JSON fields: `bitDepth` (integer, zero if unavailable), `colorRange` (empty/full/limited).
- libmpv JSON fields: `bitDepth` (integer, zero if unavailable), `colorRange` (empty/full/limited).
- Shared UI reads those fields and displays only valid values. The Selected Quality section reads `PlayerQualityVariant.videoRange` and `frameRate`.

- [x] **Step 1: Add source metadata to ExoPlayer JSON**
  - Reuse `Format.exoBitDepth()` and `Format.colorInfo.colorRange`.
  - Emit zero/empty defaults when no selected video format exists.
- [x] **Step 2: Add source metadata to libmpv JSON**
  - Derive bit depth from `video-params/pixelformat`, not post-filter `video-out-params/pixelformat`.
  - Normalize only mpv `full` and `limited` color-level values.
- [x] **Step 3: Render the additional rows**
  - Show selected HLS variant's declared dynamic range and frame rate.
  - Show actual bit depth and color range when known.
  - Render `hdr` as `HDR (PQ)`; omit an unknown dynamic-range row.
- [x] **Step 4: Update canonical engineering notes and plan**
  - Keep the main research in `docs/engineering/codebase-improvement-notes.md`.
  - Add only a short pointer to `docs/engineering/player-stream-startup-optimization-2026-11-19.md`.
  - Record that Gradle tests/build/CI/device verification were not run.

## Verification boundary

**Execution status:** Source changes and regression-test sources are integrated. All Gradle tests remain unexecuted because explicit build/test authorization has not been granted. Do not mark the plan as runtime-verified.

Perform remote source review, test-source review, diff review and documentation checks only. The common and Android host test sources are authored but not executed. TDD RED/GREEN results are therefore unverified. No Gradle command, compilation, build, CI, APK/AAB generation, benchmark or device playback is authorized in this task.
