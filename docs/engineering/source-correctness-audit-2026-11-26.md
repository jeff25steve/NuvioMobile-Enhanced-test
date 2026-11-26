# Source correctness audit — 26 November 2026

Branch: `main-build-testing`

This review extends beyond Gradle/CI configuration into playback, shared home/catalog behavior, iOS artwork rendering, networking, downloads, profile protection and authentication/session lifecycle. Findings below are confirmed from the branch's source and tests; this is not a claim that every file in the repository has been exhaustively proven correct.

## Confirmed corrections

### Playback: subtitle request metadata and continuity

- `PlayerEngine.android.kt` previously built `SubtitleRequestHeaderDataSourceFactory` with a captured subtitle list. The long-lived MediaSource/DataSource factory could therefore continue applying old request headers after subtitle metadata refreshed.
- The factory now accepts a provider. The player surface stores the latest immutable subtitle list in an `AtomicReference`, updated after successful Compose recomposition, and each DataSource reads the latest list when opening a request.
- Subtitle metadata changes no longer invalidate ExoPlayer's `remember` key solely to refresh headers. This avoids rebuilding the player and losing its active state.
- When the resolved media item genuinely changes and must be prepared again on the same player, the prepare path now prefers explicit decoder/session recovery positions, then the active player's current position, then the initial resume position. It does not treat an IDLE player's unstarted zero position as a replacement for an initial resume point.
- Regression coverage added in `SubtitleRequestHeaderDataSourceTest.kt` and `PlaybackStallResumeTest.kt`.

### Home: hero item ownership during progressive refresh

- The retained-hero check previously used full `MetaPreview` data-class equality. A refreshed item with the same media identity but updated title/artwork could be treated as an unrelated item and removed while catalog resolution was still pending.
- The check now uses the repository's stable `type:id` identity through `MetaPreview.stableKey()`.
- Regression coverage added in `HomeRepositoryProjectionTest.kt`.

### Android: respect static-versus-animated artwork policy

- The Android `NuvioAsyncImage` implementation accepted `animateIfPossible` but ignored it. With Coil's GIF decoder registered, animated GIF/WebP assets could animate even when callers requested static artwork.
- For animated file types when animation is not allowed, the request now selects Coil 3.6.3's per-request `BitmapFactoryDecoder.Factory`, which renders a static first frame without replacing the shared ImageLoader's network/cache/SVG components.
- When animation is explicitly allowed, the default animated decoder pipeline remains active. Static formats continue using the default decoder pipeline.
- Regression coverage added in `NuvioAsyncImageDecoderPolicyTest.kt`.

### iOS: animated artwork fallback

- The animated-image unavailable state previously loaded the original URL directly, bypassing the optional static cover fallback.
- It now uses the same `StaticImageWithFallback` path as non-animated artwork, so a failed animated asset can fall back to its static cover.
- Existing Android fallback behavior and the common image API remain unchanged.

### Android networking: duplicate error interceptor

- Ktor 3.4.1's OkHttp engine creates its client with `preconfigured.newBuilder()` and then applies the engine configuration. The shared OkHttp client already installs `NetworkErrorInterceptor`.
- Removed the second registration from the Ktor engine configuration. The interceptor remains on the shared client for both Ktor and direct OkHttp users, but now runs once per request.

### Profiles: PIN verification recovery and feedback

- The PIN entry coroutine previously reset its verifying state only on a normal result. A propagated cancellation or unexpected exception could leave the keypad stuck in its verifying state.
- Verification now clears the entered PIN and always releases the verifying state in `finally`; cancellation is still rethrown so structured concurrency remains intact.
- The entry UI now displays the repository's specific failure message (including offline-verification requirements) before falling back to the generic incorrect-PIN message.

### Android native build cache

- The custom cacheable Cargo/NDK task now fingerprints Rust/Cargo/cargo-ndk versions, relevant compiler and linker environment overrides, Cargo/Rustup configuration files in the workspace ancestor chain and Cargo home, plus the existing source/lockfile/ABI/minSdk/NDK inputs.
- The native invocation uses `--locked` so dependency resolution cannot mutate the declared lockfile input while producing cacheable outputs.

## Areas reviewed without a confirmed new defect

- DoH resolver/cache generation handling and DNS TTL parsing, including its host regression tests.
- Android download generation ownership, pause/resume, range validation, cancellation, process-death recovery and worker tests.
- Auth session reset serialization and cancellation/handoff behavior.
- Profile PIN storage encryption and biometric key lifecycle. PIN verifier payloads use per-entry random salts and platform-protected storage; Android uses AES-GCM with an Android Keystore key and iOS uses Keychain.
- iOS libmpv subtitle-font isolation; the dedicated cache directory for the selected font is already present on this branch.
- Current Android/Kotlin/Gradle toolchain compatibility. No unsupported version bump was made.

## Verification boundary

- Source and regression-test changes were committed and re-fetched from GitHub for review.
- No Gradle test task, Android APK/AAB build, iOS build or CI run is claimed as successful by this document.
- The Android benchmark workflow currently has a manual trigger and CI-branch push filters; the available GitHub integration can inspect workflow runs but does not expose a workflow-dispatch action. A supported trigger route must be established before claiming that verification ran.
