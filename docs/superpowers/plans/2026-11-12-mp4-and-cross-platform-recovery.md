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
