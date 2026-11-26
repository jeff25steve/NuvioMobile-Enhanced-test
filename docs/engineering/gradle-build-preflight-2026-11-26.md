# Android Gradle and APK workflow preflight — 26 November 2026

Branch: `main-build-testing`

## Findings and fixes

- The previous build toolchain was outside Kotlin's published fully supported compatibility range: Kotlin Gradle Plugin 2.4.10 was paired with Gradle 9.8.2 and AGP 9.4.0. The Kotlin compatibility matrix lists 2.4.0–2.4.10 through Gradle 9.5.0 and AGP 9.1.0.
- Aligned the toolchain to Kotlin 2.4.21, Gradle 9.7.0 and AGP 9.3.1. Kotlin's current compatibility matrix lists 2.4.20–2.4.21 through Gradle 9.7.0 and AGP 9.3.1. AGP 9.3 requires Gradle 9.5.0 or newer.
- Advanced Compose Multiplatform and its Material3 artifact from 1.13.0-beta01 to 1.13.0-rc01. The RC is the latest Compose Multiplatform prerelease available on this review date; it requires Android compileSdk 37.1 and uses the matching Compose compiler plugin version from Kotlin.
- Kept the Android compile SDK at API 37.1 and aligned the Baseline Profile test module's compile SDK minor level with the application modules.
- Pinned cargo-ndk 4.1.2 in the release and fullDebug workflows, matching the existing benchmark workflows.
- Bounded the Gradle and Kotlin daemon heaps and Gradle worker count in the fullDebug and release APK jobs. The repository-level 12 GiB / 8 GiB JVM defaults are not appropriate as unbounded CI defaults on hosted runners.
- Converted the multiline Gradle invocations to YAML block scalars. This was necessary after adding memory flags; keeping continuation lines outside a block scalar would make the workflow invalid YAML.

## Current intended Android toolchain

| Component | Version | Reason |
|---|---:|---|
| Android Gradle Plugin | 9.3.1 | Kotlin 2.4.21 fully-supported upper bound; Android API 37.1 support |
| Gradle wrapper | 9.7.0 | Kotlin 2.4.21 fully-supported upper bound; above AGP 9.3 minimum 9.5.0 |
| Kotlin / Compose compiler / serialization plugin | 2.4.21 | Stable Kotlin release and KMP compatibility alignment |
| Compose Multiplatform | 1.13.0-rc01 | Latest available RC; requires compileSdk 37.1 |
| Compose Material3 | 1.13.0-rc01 | Matches Compose Multiplatform RC |
| NDK | 28.2.13676358 | Existing pinned native toolchain |
| cargo-ndk | 4.1.2 | Same pinned version across Android build workflows |

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
