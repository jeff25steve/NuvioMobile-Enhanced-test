# Android NDK and Player Capture Hardening Implementation Plan

> **For agentic workers:** This plan was executed inline against `main-build-testing`. It remained build-free because the user prohibited Gradle tests, builds, and CI during this task.

**Goal:** Pin the Android NDK used by the Android app/Rust build, make Cargo's NDK environment consistent, and strengthen Player capture edge-case coverage.

**Architecture:** AGP selects a pinned NDK version through the Android app module. The existing custom Cargo task derives the concrete selected directory once, verifies its directory name against its task input, and exports that same path through both NDK environment variables. Existing Android host tests gain additional PixelCopy error and malformed-dimension cases.

**Tech Stack:** Android Gradle Plugin 9.4.0, Android NDK 28.2.13676358, Kotlin 2.4.10, Android SDK 37.1, PixelCopy API, Kotlin test.

**Spec:** `docs/superpowers/specs/2026-11-14-android-ndk-and-player-capture-design.md`

## Global Constraints

- Do not start builds, Gradle tasks, tests, or CI.
- Do not modify GitHub workflow files or dispatch workflows.
- Preserve current Rust crate, ABI list, minimum SDK, and Gradle task graph.
- Do not change PixelCopy callback dispatch, frame cadence, or bitmap ownership.
- Do not claim tests or builds pass when they have not been run.

## Review Focus

- NDK version selected by AGP must match the directory name consumed by the Rust task.
- Both Cargo NDK environment variables must resolve to the same selected directory.
- Failed PixelCopy statuses must remain unavailable and must never be treated as successful video.
- Invalid or overflowing bitmap dimensions must return null before indexing or allocation.

## Task 1: Pin and align the NDK environment

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `androidApp/build.gradle.kts`

**Changes:**
- Add version catalog entry `android-ndk = "28.2.13676358"`.
- Set `android.ndkVersion = libs.versions.android.ndk.get()`.
- In `CargoNdkBuild.build()`, resolve the NDK directory once, validate its basename against the `ndkVersion` input, and set both `ANDROID_NDK_HOME` and `ANDROID_NDK_ROOT` to its absolute path.

**Verification:** Static source review only. Gradle was not run by instruction.

## Task 2: Strengthen Player capture regression coverage

**Files:**
- Modify: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/PlayerLiquidGlassCaptureLifecycleTest.kt`

**Changes:**
- Extend failed PixelCopy status coverage to include `ERROR_UNKNOWN` and `ERROR_DESTINATION_INVALID`.
- Add an overflow case with a 50,000 × 50,000 declared source and a one-pixel input buffer; assert `downsampleArgb` returns null.

**Verification:** Static review only. The host test command was intentionally not run because builds/tests were not authorised.

## Task 3: Update engineering records

**Files:**
- Modify: `docs/engineering/codebase-improvement-notes.md`

Record the NDK mismatch evidence, pin/alignment change, expanded regression coverage, and explicit unverified status.

## Completion evidence

- Compare the final remote branch against the starting SHA.
- Confirm no workflow file was added or modified.
- Confirm remote commit ancestry and changed-file set.
- Leave Gradle/test/build verification pending by instruction.
