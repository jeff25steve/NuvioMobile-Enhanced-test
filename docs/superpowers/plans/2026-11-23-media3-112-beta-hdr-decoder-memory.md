# Media3 1.12.0-beta01 HDR and decoder improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adopt Media3 1.12.0-beta01 on the testing branch to improve HLS HDR metadata handling, Dolby Vision decoder compatibility and sample-buffer memory use.

**Architecture:** Update the single Media3 version-catalog alias so every Media3 module stays aligned. Add a static compatibility guard for the minimum SDK and known removed APIs, then update the canonical player engineering notes with upstream evidence and the explicit validation boundary.

**Tech Stack:** Kotlin Multiplatform, Android, Gradle version catalog, AndroidX Media3 1.12.0-beta01, shell static checks.

**Spec:** docs/superpowers/specs/2026-11-23-media3-112-beta-hdr-decoder-memory-design.md

## Global Constraints

- Work only on `main-build-testing`; no force-push.
- Keep all Media3 artifacts aligned through `gradle/libs.versions.toml`.
- Project minSdk must remain 24 or higher.
- Do not change iOS, libmpv, custom renderer ordering, player buffers, decoder priority, frame-rate policy or user settings.
- Do not enable MediaCodec renderer prewarming.
- No Gradle tasks, tests, compilation, builds, CI, APK/AAB, benchmarks or device playback without explicit authorization.

## Review Focus

- HLS SDR, PQ and HLG variants: metadata must reach Media3 as `Format.colorInfo`; verify on HDR and SDR displays before release.
- Dolby Vision Profile 7: HEVC fallback must work when a compatible DV decoder is absent.
- Unsupported Dolby Vision profile: decoder selection must not silently treat an unsupported DV profile as ordinary HEVC with incorrect colors.
- Progressive sources with multiple video tracks: changing to an unselected track may require additional loading under the new default; verify user-visible behavior.
- API 24 devices: the new Media3 minimum must not raise the app's existing minimum SDK.

---

### Task 1: Align the Media3 dependency family

**Files:**
- Modify: `gradle/libs.versions.toml`

**Interfaces:**
- Consumes: existing `androidx-media3` version alias.
- Produces: all catalog-managed Media3 modules resolve to `1.12.0-beta01`.

- [x] Change only the `androidx-media3` version alias from `1.11.1` to `1.12.0-beta01`.
- [x] Confirm every Media3 module continues to use `version.ref = "androidx-media3"`.
- [x] Confirm `android-minSdk = "24"` remains unchanged.

### Task 2: Add a static compatibility guard

**Files:**
- Create: `scripts/tests/test-media3-112-beta-compatibility.sh`

**Interfaces:**
- Consumes: version catalog and production source under `composeApp/src`.
- Produces: nonzero exit on version drift, minSdk below 24, or known removed API references.

- [x] Check the version alias is exactly `1.12.0-beta01`.
- [x] Check the minSdk catalog value is at least 24.
- [x] Check all catalog Media3 module aliases reference the common version alias.
- [x] Fail if production source references `DashUtil.buildDataSpec`, `setAsyncCryptoSynchronizationEnabled` or `TextureAssetLoader`.
- [x] Print a clear success summary; do not invoke Gradle or network commands.

### Task 3: Update the existing engineering notes

**Files:**
- Modify: `docs/engineering/codebase-improvement-notes.md`
- Modify: `docs/engineering/player-stream-startup-optimization-2026-11-19.md`

- [x] Record the exact upstream HDR, Dolby Vision and sample-buffer changes.
- [x] Record the progressive selected-track loading tradeoff and manual validation matrix.
- [x] Record that the dependency bump and static checks are source-only until builds/tests/device playback are authorized.
- [x] Re-fetch changed files from the remote branch and review the complete diff.

## Verification

The static guard is added but intentionally not executed under the current no-test/no-build authorization boundary. No compilation, Gradle test, CI, APK/AAB or physical-device playback is claimed. Before a production release, run the full Android build/test suite and the device matrix in the design document.
