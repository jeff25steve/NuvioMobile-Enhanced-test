# HLS quality metadata labels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show valid HLS dynamic-range and frame-rate metadata in the existing manual quality chooser without changing playback selection.

**Architecture:** Extend the common `PlayerQualityVariant` model with optional metadata. Parse standard `VIDEO-RANGE` and `FRAME-RATE` attributes in the existing HLS master resolver and compose concise labels through the existing `displayLabel` property. The current panel already displays that property, so no panel layout change is needed.

**Tech Stack:** Kotlin Multiplatform common Kotlin, Compose Multiplatform UI, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-23-hls-quality-metadata-design.md`

## Global Constraints

- Preserve Auto adaptive playback and manual one-variant playlist behavior.
- Do not change Android/iOS player engines, decoder selection, track selection or quality recommendation.
- Do not add dependencies.
- Unknown range and invalid frame-rate values are omitted.
- No Gradle build, test, or CI execution without explicit authorization.

## Review Focus

- HLS range attribute casing and whitespace — normalization tests.
- Unsupported or absent range values — ensure no misleading label.
- NaN, infinity, zero, negative and malformed frame rates — ensure they are omitted.
- Fractional rates such as 23.976 and 59.94 — ensure concise two-decimal labels.
- Existing variants without metadata — ensure their display labels are unchanged.

---

### Task 1: Add common metadata behavior tests

**Files:**
- Create: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/PlayerQualityVariantMetadataTest.kt`

**Interfaces:**
- Consumes: `playerVideoRangeLabel(value: String?): String?`, `parsePlayerFrameRate(value: String?): Double?`, and `PlayerQualityVariant.displayLabel`.
- Produces: executable common test cases pinning the expected labels and validation behavior.

- [x] **Step 1: Write tests first**
  - `PQ`, `HLG`, and `SDR` normalize case-insensitively to `HDR (PQ)`, `HDR (HLG)`, and `SDR`.
  - Unknown and null range values return null.
  - `23.976` and `59.94` parse and display as `23.98 fps` and `59.94 fps`.
  - null, malformed, zero, negative, NaN and infinite frame rates return null.
  - A combined variant label includes range and frame rate; a variant without metadata keeps its old label.
- [ ] **Step 2: Run the focused common test**
  - Run: `./gradlew :composeApp:allTests`
  - Expected: fail because the helpers and metadata fields do not exist yet.
  - Status: not executed; Gradle test/build execution has not been authorized, so the TDD red result is unverified.
- [x] **Step 3: Implement the minimal common metadata model and parser**
  - Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerQualitySelector.kt`
  - Add optional `videoRange: String? = null` and `frameRate: Double? = null` to `PlayerQualityVariant`.
  - Add `playerVideoRangeLabel(value: String?): String?` and `parsePlayerFrameRate(value: String?): Double?`.
  - Parse `VIDEO-RANGE` and `FRAME-RATE` in `HlsMasterPlaylist.parse`, carry them through `HlsVariant.toUiVariant`, and append valid labels in `displayLabel`.
  - Keep all existing playback and recommendation behavior unchanged.
- [ ] **Step 4: Run focused and full common tests**
  - Run: `./gradlew :composeApp:allTests`
  - Expected: pass with no regressions.
  - Status: not executed; Gradle test/build execution has not been authorized, so the green result is unverified.
- [x] **Step 5: Review source and update the engineering notes**
  - Modify: `docs/engineering/codebase-improvement-notes.md` with the canonical implementation/research record.
  - Modify: `docs/engineering/player-stream-startup-optimization-2026-11-19.md` with a short pointer, not duplicated research.
  - Re-fetch every changed remote file and inspect the final diff.

## Verification boundary

Source-level review and GitHub remote diff inspection were performed. The test source was written before production code, but neither RED nor GREEN was observed because Gradle execution was not authorized. Compilation, Gradle tests, builds, CI, APK/AAB generation, benchmarks and device playback remain unexecuted until separately authorized.
