# Phone benchmark installed-APK provenance — design

**Date:** 19 November 2026  
**Branch:** `main-build-testing`  
**Starting commit:** `a5684be409943e74532f7fa7199d602f83cf0831`

## Intent

Make every phone-only profile collection and Macrobenchmark result identify the exact app and self-instrumenting test APK pair that was installed, even when installation and measurement happen in separate LADB shell invocations. When Android exposes readable installed APK paths, compare their bytes with the recorded source APK hashes and stop before instrumentation if either installed package has drifted.

## Evidence and current defect

- `install_variant()` knows the app and test APK paths and hashes at install time, but it currently writes only `ACTION:FLAVOR` to `current-install.txt`.
- `write_run_provenance()` reads `$APP_APK` and `$TEST_APK` as process variables. A later `collect` or `measure` command starts a new shell and those variables are unset; the combined `run` profile-capture phase also reaches provenance writing before `install_variant()` sets them.
- Therefore the existing `provenance.txt` can omit the APK source paths/hashes for valid, separately invoked collection or measurement runs.
- The phone bundle contains a single APK for each app/test variant. AOSP's `pm path PACKAGE` returns the installed base APK path and any installed split paths. Device policy does not guarantee that the shell can read every installed `/data/app` APK, so byte verification must distinguish a mismatch from an inaccessible file.

## Goals

1. Persist the source APK filename and SHA-256 for both packages when the pair is installed.
2. Load and validate that install record on a later profile-collection or benchmark invocation.
3. Include the persisted source APK identity in every run's `provenance.txt`, independent of process-local shell variables.
4. Query installed code paths with `pm path`. A known byte mismatch must stop before `am instrument`; unreadable/unsupported installed code paths must be explicitly reported as unverified rather than falsely treated as a match.
5. Make local SHA-256 failures return an explicit `unavailable` value instead of silently emitting an empty field.
6. Keep production `com.nuvio.media` untouched. Do not change app code, benchmark APIs, dependencies, build configuration, workflow triggers or benchmark timing.

## Non-goals

- No attempt to change app startup speed or add extra work during measured iterations.
- No root requirement. No direct inspection of app-private data.
- No claim of installed-byte verification on devices that deny shell file access.
- No build, CI, instrumentation or physical-device run in this work.

## Approaches considered

1. **Persist install-time provenance only.** This fixes empty paths/hashes across LADB invocations, but cannot detect a package replaced after installation.
2. **Require reading and hashing installed APKs on every device.** Strongest byte identity, but AOSP/device policy does not guarantee shell access to all installed APK paths, so it would reject valid OEM devices unnecessarily.
3. **Chosen: persisted source provenance plus best-effort installed-byte verification.** It always preserves the exact source artifact identity, compares actual installed bytes whenever accessible, fails closed on a known mismatch, and reports inaccessible bytes as unverified. This gives useful assurance without root or a false guarantee on hardened devices.

## Implementation decisions

- Reuse the existing output-root state directory and keep `current-install.txt` as the lightweight variant marker for compatibility with current scripts.
- Add `current-install-provenance.txt` containing the variant, source APK basenames, and validated SHA-256 values.
- Keep all package-manager checks before profile collection or Macrobenchmark instrumentation starts.
- Accept exactly one `package:` APK path per installed package because the distributed bundle supplies one standalone APK per package; unexpected split installs are a package-identity mismatch.
- When the installed APK path is returned but cannot be read, record `UNVERIFIED` and continue. When it can be read and differs from the saved source hash, fail before instrumentation.

## Verification

- Extend the phone-runner shell fixture so its package-manager stub installs test APK bytes, exposes `pm path`, and can simulate app/test package drift and unreadable installed APK paths.
- Verify source provenance survives separate command invocations and is correct for both NonMinifiedRelease profile capture and BenchmarkRelease measurement.
- Verify mismatched installed app/test APKs are rejected before `am instrument`, while unreadable paths are recorded as unverified.
- Update the static metric-contract test and engineering notes.
- No Gradle task, build, CI or real-device benchmark is permitted.
