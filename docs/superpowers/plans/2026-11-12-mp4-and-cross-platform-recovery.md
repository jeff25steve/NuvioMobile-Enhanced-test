# MP4 and Cross-Platform Recovery — 12 November 2026

**Repository:** `jeff25steve/NuvioMobile-Enhanced-test`  
**Target:** `main-build-testing`

## Confirmed root causes

- Android fullDebug #123 and iOS Debug #1 failed because Coil 3.6.3's Android-only `allowHardware` extension was called from `commonMain`.
- iOS additionally lacked the Native `kotlinx.coroutines.IO` extension import, referenced an undeclared source-load semaphore, inferred NSURLSession's returned array as nullable/generic, and treated CFData's pointer as a signed byte pointer without an explicit type conversion.
- The MP4 source on the latest branch had become the literal `PLACEHOLDER`, and an earlier intermediate copy omitted `video_keyframes`.

## Integrated fixes

- [x] Restore the complete MP4 parser from the last complete revision.
- [x] Preserve and wire `stsz`, `stco`, and `co64` validation into the active keyframe parser.
- [x] Add regression tests for fixed/variable sample sizes and 32-/64-bit chunk offsets.
- [x] Isolate Coil hardware bitmap configuration behind common expect and Android/iOS actuals.
- [x] Import `kotlinx.coroutines.IO` for shared Native dispatcher access.
- [x] Restore the independent bounded iOS source-load semaphore.
- [x] Narrow nullable NSURLSession candidates before accessing task properties or cancellation.
- [x] Reinterpret CFData byte pointers as `ByteVar` before indexed reads.
- [x] Update engineering notes and replace the obsolete restore warning with a recovery record.

## Research

Context7: Coil 3.6.3, kotlinx.coroutines 1.10.x and Kotlin/Native C interop. Exa: Coil platform API discussion, native pointer usage and Foundation URLSession task-list evidence. No dependency upgrades.

## Still to verify

- [ ] Review final Git diff and verify the remote branch commit.
- [ ] Run Rust parser regression tests (not yet executed).
- [ ] Run Android fullDebug APK workflow from repaired target commit.
- [ ] Run iOS Debug IPA workflow from the same target commit.
- [ ] Confirm each workflow's success conclusion and uploaded artifact.

Do not force-push or alter `enhanced`. Do not report tests or builds as passing until actual results are visible.


## Validation result and Grok performance checkpoint — 12 November 2026

- Linux fullDebug workflow [#43153877235](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43153877235) passed on source commit 87cdeb95896fe984d553ad92c83660919ad7d13b. Native container tests passed, APK build passed, and artifact androidApp-full-debug.apk (ID 13915911263, 168,811,025 bytes) uploaded. Retrieved package SHA-256: a7a4effc200349248c7cc7212f458759599078990aeb9d0bb7bca09582462889.
- iOS workflow [#43153877203](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43153877203) failed only at the remaining pointer indexing expression in TraktAuthStorage.ios.kt:118; no IPA was uploaded. Keep as a separate follow-up and do not start another iOS run until repaired.
- Reviewed Grok's speed/load-time notes against current sources. The repository already has the Baseline Profile module/plugin, startup+home-scroll journey generator, ProfileInstaller integration and a 1,409,345-byte checked-in baseline-prof.txt. Macrobenchmark dependency is already 1.5.0; AGP is 9.4.0 and Compose Multiplatform is 1.13.0-beta01. Release minification/shrinking default to enabled. Do not add a second benchmark system or change dependencies.
- Next measurement: physical mid-range Android, profileable non-debuggable release-like target, repeated StartupMode.COLD measurements; compare CompilationMode.None() with Partial + required Baseline Profile; capture startup timing and a deterministic first-home-content/time-to-interactive marker. Verify the compiled profile is present in release output and inspect its documented 1.5 MB limit. The 150–300 ms figure is a hypothesis, not an expected or promised gain.
- No speed or UI code changes were made. Do not move auth/profile gates, add generic lazy initialization, change image policy broadly, blanket-convert collections to Sequence, or adjust iOS/Compose code without measurements.
