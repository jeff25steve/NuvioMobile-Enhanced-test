# Android Gradle and APK workflow preflight — 26 November 2026

Branch: `main-build-testing`

> **Build-runbook correction:** The proven Android CI execution on 26 November is documented in [the Android build workflow runbook](android-build-workflow-runbook-2026-11-26.md). That successful run (#138) built `main-build-testing` directly on a `push`, using Gradle 9.9.2, Temurin 17.0.21+9, AGP 9.3.1 and Kotlin 2.4.21. This is the observed CI tuple; it differs from the 9.7.0 compatibility-analysis target recorded below. Do not confuse the proposed compatibility target with the actual successful CI configuration.

## Findings and fixes

- The previous build toolchain was outside Kotlin's published fully supported compatibility range: Kotlin Gradle Plugin 2.4.10 was paired with Gradle 9.8.2 and AGP 9.4.0. The Kotlin compatibility matrix lists 2.4.0–2.4.10 through Gradle 9.5.0 and AGP 9.1.0.
- Aligned the toolchain to Kotlin 2.4.21, Gradle 9.7.0 and AGP 9.3.1. Kotlin's current compatibility matrix lists 2.4.20–2.4.21 through Gradle 9.7.0 and AGP 9.3.1. AGP 9.3 requires Gradle 9.5.0 or newer.
- Advanced Compose Multiplatform and its Material3 artifact from 1.13.0-beta01 to 1.13.0-rc01. The RC is the latest Compose Multiplatform prerelease available on this review date; it requires Android compileSdk 37.1 and uses the matching Compose compiler plugin version from Kotlin.
- Kept the Android compile SDK at API 37.1 and aligned the Baseline Profile test module's compile SDK minor level with the application modules.
- Pinned cargo-ndk 4.1.2 in the release and fullDebug workflows, matching the existing benchmark workflows.

- This preflight recorded the Gradle 9.7.0 binary checksum as part of its compatibility-analysis plan. However, the exact wrapper at the successful 26 November run #138 points to Gradle 9.9.2 with SHA-256 `971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b`. See the runbook for the actual successful workflow and version record.
- Bounded the Gradle and Kotlin daemon heaps and Gradle worker count in the fullDebug and release APK jobs. The repository-level 12 GiB / 8 GiB JVM defaults are not appropriate as unbounded CI defaults on hosted runners.
- Converted the multiline Gradle invocations to YAML block scalars. This was necessary after adding memory flags; keeping continuation lines outside a block scalar would make the workflow invalid YAML.

## Compatibility-analysis candidate (not the tuple used by successful run #138)

| Component | Version | Reason |
|---|---:|---|
| Android Gradle Plugin | 9.3.1 | Kotlin 2.4.21 fully-supported upper bound; Android API 37.1 support |
| Gradle wrapper | 9.7.0 | Kotlin 2.4.21 fully-supported upper bound; above AGP 9.3 minimum 9.5.0 |
| Kotlin / Compose compiler / serialization plugin | 2.4.21 | Stable Kotlin release and KMP compatibility alignment |
| Compose Multiplatform | 1.13.0-rc01 | Latest available RC; requires compileSdk 37.1 |
| Compose Material3 | 1.13.0-rc01 | Matches Compose Multiplatform RC |
| NDK | 28.2.13676358 | Existing pinned native toolchain |
| cargo-ndk | 4.1.2 | Same pinned version across Android build workflows |

## Version freshness and compatibility decision — 26 November 2026

- Kotlin's latest stable release is 2.4.21. Kotlin 2.5.0 is still a release candidate, so the project stays on the stable compiler line.
- This compatibility review recorded Kotlin 2.4.21's published fully supported range as topping out at Gradle 9.7.0 and AGP 9.3.1; it therefore proposed those versions as a compatibility-analysis target. However, the successful run #138 subsequently verified the repository's actual Gradle 9.9.2 wrapper with AGP 9.3.1 and Kotlin 2.4.21. Treat that as empirical project CI evidence, not proof that the tuple is inside every upstream compatibility guarantee. The statement that the wrapper was intentionally kept at 9.7.0 is not an accurate description of the successful branch state. Do not downgrade or upgrade a single component based on this note; re-check current official compatibility documentation before any coordinated toolchain change.
- Compose Multiplatform 1.13.0-rc01 remains the latest Compose Multiplatform release candidate checked for this review.
- AndroidX Media3 has a newer stable line (1.11.1), but this project and the upstream Nuvio repository still use Media3 1.8.0 with locally supplied/patched Media3 AARs. A version bump is not a safe catalog-only edit: the patched AAR set and all player integrations must be upgraded and validated together. No unverified Media3 upgrade was made.

## Native Rust task cache correctness

- The Android `CargoNdkBuild` task produces ABI-specific native libraries and is marked `@CacheableTask`. Gradle's cache contract requires every value that can affect those outputs to be represented in the task inputs; omitting one can cause incorrect cache hits.
- Added a lazy task-input fingerprint for the selected `rustc`, Cargo and `cargo-ndk` versions, plus relevant Rust/Cargo/native compiler environment overrides (toolchain selection, wrappers, target, flags, release profile, NDK target/platform and C/C++ tool overrides).
- Added absolute-path-sensitive file inputs for Cargo configuration (`.cargo/config` / `.cargo/config.toml`) and Rustup `rust-toolchain` / `rust-toolchain.toml` files throughout the native workspace's parent-directory chain, plus both legacy and TOML Cargo config files under `$CARGO_HOME`. Changes to linker, target, profile, compiler or toolchain configuration now invalidate native outputs even when the environment itself is unchanged.
- The existing source/Cargo.lock file inputs, ABI list, minimum SDK and NDK version remain explicit. The fingerprint is a task input, so a compiler or flag change invalidates both local up-to-date state and shared build-cache entries.
- The native Cargo invocation now uses `--locked`, so dependency resolution cannot rewrite `Cargo.lock` while the cacheable task is producing its outputs.
- `providers.exec` supplies the version strings lazily; the build script does not eagerly execute compiler probes during project configuration.

## Workflow paths reviewed

- `.github/workflows/mobile-release.yml` — release APK build and upload.
- `.github/workflows/pr-full-debug-build.yml` — fullDebug APK build and upload.
- `.github/workflows/android-startup-profile-benchmark.yml` — manual profile-capture/R8 APK pairs and phone-ready ZIP bundle.
- `.github/workflows/android-startup-profile-benchmark-ci.yml` — CI-branch benchmark build and source freshness guard.
- `androidApp/build.gradle.kts`, `composeApp/build.gradle.kts`, `baselineProfile/build.gradle.kts`.
- `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `gradle.properties`.

## Verification boundary

The repository files and workflow commands were inspected through GitHub. Workflow YAML structure and changed command indentation were re-fetched and statically checked. The connected GitHub actions capability available in this session is read-only for workflow dispatch; it cannot start a new run on the current commit. The local container also could not resolve github.com, so a local checkout could not be obtained.

Therefore no Gradle configuration, compilation, host test, APK assembly, phone-bundle assembly, or current-commit CI run is claimed as passing. A successful historical benchmark run on a different CI-branch commit is not treated as verification of this branch. Run the manual Android Startup Profile Benchmark workflow and the Mobile Release workflow in `test-build` / `android-only` mode against this exact branch head before declaring the build green.

## Primary compatibility references

- Kotlin Gradle plugin compatibility: https://kotlinlang.org/docs/gradle-configure-project.html
- Kotlin Multiplatform compatibility: https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html
- Android Gradle Plugin compatibility and API support: https://developer.android.com/build/releases/about-agp
- AGP 9.3 release notes: https://developer.android.com/build/releases/agp-9-3-0-release-notes
- Compose Multiplatform 1.13.0-rc01: https://kotlinlang.org/docs/multiplatform/whats-new-compose-113.html
- Gradle 9.7 compatibility: https://docs.gradle.org/9.7.0/userguide/compatibility.html

## Proven Android build execution

The exact CI run, branch, trigger, verified toolchain, staged Gradle tasks, APK artifacts and future reproduction checklist are recorded in [Android build workflow runbook — 26 November 2026](android-build-workflow-runbook-2026-11-26.md). Use that runbook as the operational reference before starting another Android build.
