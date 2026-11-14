# Android NDK and Player Capture Hardening — Design

**Date:** 14 November 2026  
**Base:** `main-build-testing` at `add3a775b87227b9d5c45762103cc956f2814181`

## Intent

Make the Android Rust/native build select one reproducible NDK version and pass that exact selected path consistently to `cargo-ndk`. Extend the existing Player capture tests to cover all documented PixelCopy failure statuses and malformed pixel dimensions before any future capture refactor.

## Goals

- Pin the app module to NDK `28.2.13676358`, the exact NDK selected in the last successful fullDebug run.
- Keep the Gradle-selected NDK directory and the `ndkVersion` task input aligned.
- Set both `ANDROID_NDK_HOME` and `ANDROID_NDK_ROOT` to that selected NDK for the `cargo-ndk` subprocess.
- Retain the PixelCopy contract that only `PixelCopy.SUCCESS` accepts a frame.
- Cover `ERROR_UNKNOWN` and `ERROR_DESTINATION_INVALID`, as well as integer-overflowing image dimensions, in host regression tests.

## Non-goals

- No workflow changes, CI dispatches, Gradle invocations, tests, or APK builds during this task.
- No PixelCopy callback-thread/executor change until real-device traces show that moving the callback materially improves latency.
- No change to playback engine selection, frame rate, bitmap ring-buffer ownership, or Liquid Glass rendering.

## Approaches

1. **Set only `ANDROID_NDK_HOME`:** insufficient because the successful CI log showed a conflicting inherited `ANDROID_NDK_ROOT`.
2. **Pin the NDK and set both environment variables from Gradle's selected directory:** chosen. This gives AGP one explicit version and ensures the Rust child process sees consistent paths.
3. **Change PixelCopy callback dispatch immediately:** deferred. The existing callback only resumes a suspended coroutine; changing its dispatcher without device measurements adds asynchronous lifecycle complexity without demonstrated user benefit.

## Constraints

- Preserve the current Gradle/Cargo commands and ABI list.
- Use the existing Android host-test source set for the PixelCopy contract tests.
- Do not mark compilation, tests, or device playback verified without fresh execution evidence.
