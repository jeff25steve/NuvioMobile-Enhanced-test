# Android build workflow runbook — 26 November 2026

**Purpose:** Preserve the exact known-good Android CI procedure so a future build request can be carried out from the correct branch, with the correct workflow, without rediscovering the process or guessing at toolchain changes.

**Read this document before starting an Android CI build.** It records one proven build and the workflow that produced it. It is not permission to start a build: only start CI when the user explicitly asks to start/build/test. Before every future run, re-check the current branch HEAD, workflow contents, wrapper, version catalog and existing Actions runs. If any of those have changed, record the difference rather than blindly replaying this historical snapshot.

## 1. Proven successful reference run

| Field | Verified value |
|---|---|
| Repository | `jeff25steve/NuvioMobile-Enhanced-test` |
| Branch that actually built | `main-build-testing` |
| Workflow | `Android Startup Profile Benchmark CI Branch Runner` |
| Workflow file | `.github/workflows/android-startup-profile-benchmark-ci.yml` |
| GitHub Actions run | [#138 — successful](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/45879964256) |
| Run ID | `45879964256` |
| Trigger | `push` |
| Commit built | `487076e34e7064926dde182ac4279fab69c4cc80` |
| Commit date | 26 November 2026, 18:57 UTC |
| Job | `Build phone-ready R8 and profile-capture APKs` |
| Result | Job and all listed steps completed successfully |

**Branch clarification:** Run #138 built directly from `main-build-testing`. It did **not** check out or build from a separate `ci/*` branch. The workflow file also lists `ci/main-build-testing-20261124` and `ci/main-build-testing-preflight-20261126` as push-trigger branches, and supports `workflow_dispatch`, but those options are not the trigger used by the successful reference run.

The workflow's manual dispatch option can be used when the user explicitly asks for a manually started run: in GitHub Actions select **Android Startup Profile Benchmark CI Branch Runner**, select branch **main-build-testing**, and start the workflow. Do not describe that as the trigger for historical run #138; it was a `push` run. Do not choose a `ci/*` branch unless the user specifically asks to test that isolated branch.

The repository default branch is `enhanced`, not `main-build-testing`. Always specify and verify the intended branch; never assume the default branch is the build target.

## 2. Exact toolchain observed in the successful run

These values are grounded in the wrapper/version catalog at commit `487076e`, the workflow's explicit checks and the successful job log.

| Component | Successful-run value |
|---|---:|
| GitHub runner image | `ubuntu-latest` (resolved by that run to Ubuntu 26.04) |
| Java distribution | Eclipse Temurin |
| Java version | 17; resolved in the run to `17.0.21+9` |
| Gradle wrapper | `9.9.2` |
| Android Gradle Plugin | `9.3.1` |
| Kotlin / Compose compiler plugin | `2.4.21` |
| Compose Multiplatform | `1.13.0-rc01` |
| Compose Material3 | `1.13.0-rc01` |
| Android compile SDK | API `37`, minor `1` |
| Android NDK | `28.2.13676358` |
| Rust target installed | `aarch64-linux-android` |
| `cargo-ndk` | `4.1.2` |
| Media3 catalog version | `1.8.0` |

The successful workflow explicitly checks Java specification version 17, Eclipse Adoptium as vendor, Gradle output `Gradle 9.9.2`, and a Gradle launcher JVM on Java 17. Do not change Java or Gradle merely to work around a Kotlin source error. Fix a source/compiler failure at its cause first.

### Important Gradle documentation discrepancy

The companion document `gradle-build-preflight-2026-11-26.md` records a Gradle 9.7.0 compatibility-analysis target and contains wording that says Gradle 9.9.2 was not adopted. That wording does not match the actual wrapper and CI workflow at the successful commit: `gradle-wrapper.properties` points to Gradle 9.9.2, has SHA-256 `971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b`, and run #138 explicitly verified and successfully used Gradle 9.9.2 with AGP 9.3.1 and Kotlin 2.4.21.

Treat run #138 as evidence that this repository's Android CI workflow completed with that exact combination. This empirical result is **not** a claim that the combination is within every upstream vendor's officially supported compatibility matrix. Do not silently downgrade to 9.7.0 or upgrade any toolchain component independently. Reconcile the compatibility analysis against current official documentation before proposing a toolchain change.

## 3. What the successful workflow does, in order

The workflow is intentionally staged. Preserve this order and keep the four APK build groups separate.

1. **Checkout** the commit that triggered the workflow.
2. **Guard against stale source.** The workflow compares `main-build-testing...GITHUB_SHA` using the GitHub compare API. It refuses to proceed unless the current `main-build-testing` HEAD is the merge base and `behind_by == 0`. This prevents building from an out-of-date CI branch.
3. **Set up Java** using `actions/setup-java@v6`, Temurin, Java 17.
4. **Set up Gradle** using `gradle/actions/setup-gradle@v6`. Cache is read-only only for pull requests; the direct branch build can write reusable Gradle state.
5. **Verify toolchains.** The shell checks Java specification version and vendor, then runs `./gradlew --version` and requires Gradle 9.9.2 and a Java 17 launcher JVM.
6. **Prepare runtime properties.** Decode `NUVIO_LOCAL_PROPERTIES_BASE64` when supplied, otherwise create an empty `local.properties`; remove machine-specific SDK, release-store and Supabase entries; resolve the current backend URL and publishable key from `https://api.nuvio.tv/.well-known/nuvio`; then append the contribution endpoint. Do not put secret values in documentation or logs.
7. **Validate benchmark scripts and contracts.** Run Bash and POSIX shell syntax checks; run `scripts/tests/test-nuvio-phone-benchmark.sh` with tracing; then run the benchmark-bundle assembly, startup-readiness and startup-metric-contract shell tests.
8. **Prepare build diagnostics.** Create separate Gradle logs, Gradle profiles, R8 reports, host-test reports, resource snapshots and timing files.
9. **Run Android host regression tests** using the shared Gradle wrapper function:
   `:composeApp:testAndroidHostTest`
10. **Install the Rust Android target** `aarch64-linux-android`, restore/cache the Cargo registry and native target, and restore/cache `cargo-ndk`. Install `cargo-ndk --version 4.1.2 --locked` only on a cache miss.
11. **Build the full profile-capture APK pair** (app + Baseline Profile test APK).
12. **Upload a full profile-capture checkpoint** so evidence remains available if a later stage fails.
13. **Build the Play Store profile-capture APK pair.**
14. **Build the full R8 benchmark APK pair.**
15. **Build the Play Store R8 benchmark APK pair.**
16. **Collect diagnostics** even after a failure: host-test JUnit XML/HTML, R8 Configuration Analyzer HTML, Gradle profiles, logs, timings and resource snapshots.
17. **Upload build diagnostics.**
18. **Assemble and validate the phone-ready bundle.** The script checks the expected APKs, package IDs, non-debuggable benchmark builds, profileability, R8 mapping files, arm64-only native libraries and packaged Baseline Profile requirements. It writes `START-HERE.txt` and `SHA256SUMS.txt`, checks the checksums, and requires exactly eight APKs plus two runner scripts and the two support files.
19. **Upload the phone-ready APK bundle.**

The successful reference run's Android host-test Gradle step completed successfully. Each of the four APK-pair build groups also reported `BUILD SUCCESSFUL`, and the final job completed with success.

## 4. Exact Gradle execution envelope

The workflow defines a helper named `run_gradle_group`. Use it for each Gradle group; do not merge the stages into one large Gradle invocation.

The helper:
- records runner CPU, memory, disk, cgroup and process snapshots before and after Gradle;
- samples runner resources every 45 seconds while Gradle is active;
- writes one log per group and records elapsed time and exit status;
- preserves the real Gradle exit code from the pipeline;
- enables stack traces, Gradle profiling and plain console output.

The helper runs this exact Gradle pipeline (including log capture and preservation of Gradle's exit status):

```bash
set +e
./gradlew "$@" \
  -Dorg.gradle.jvmargs='-Xmx6g -Xms512m -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC' \
  -Pkotlin.daemon.jvmargs='-Xmx6g -Xms1g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC' \
  --max-workers=2 \
  --stacktrace --profile --console=plain 2>&1 | tee "$log_file"
gradle_status=${PIPESTATUS[0]}
set -e
```

The workflow prints this CI budget for every group: Gradle heap at most 6 GiB, Kotlin daemon heap at most 6 GiB and Gradle workers at most 2. These are the verified CI settings from the workflow, not a direction to replace repository-wide JVM defaults without review.

The Gradle groups and tasks are:

### A. Android host tests

```bash
:composeApp:testAndroidHostTest
```

### B. Full profile-capture pair

```bash
:androidApp:assembleFullNonMinifiedRelease \
:baselineProfile:assembleFullNonMinifiedRelease \
-Pnuvio.android.distribution=full \
-Pnuvio.ios.distribution=appstore
```

### C. Play Store profile-capture pair

```bash
:androidApp:assemblePlaystoreNonMinifiedRelease \
:baselineProfile:assemblePlaystoreNonMinifiedRelease \
-Pnuvio.android.distribution=playstore \
-Pnuvio.ios.distribution=appstore
```

### D. Full R8 benchmark pair

```bash
:androidApp:assembleFullBenchmarkRelease \
:baselineProfile:assembleFullBenchmarkRelease \
-Pnuvio.android.distribution=full \
-Pnuvio.ios.distribution=appstore
```

### E. Play Store R8 benchmark pair

```bash
:androidApp:assemblePlaystoreBenchmarkRelease \
:baselineProfile:assemblePlaystoreBenchmarkRelease \
-Pnuvio.android.distribution=playstore \
-Pnuvio.ios.distribution=appstore
```

Keep A–E as separate `run_gradle_group` calls, in that order. The workflow comment records that a combined task graph previously exhausted the Kotlin daemon heap. Do not combine them as a speculative speed optimisation.

## 5. Artifacts and what they prove

The successful workflow is designed to upload:

| Artifact name pattern | Contents / purpose |
|---|---|
| `nuvio-android-startup-full-profile-checkpoint-<commit-sha>` | Early diagnostics checkpoint after the full profile-capture pair |
| `nuvio-android-startup-build-diagnostics-<commit-sha>` | Gradle logs/profiles, host-test reports, R8 reports, timings and runner-resource evidence |
| `nuvio-android-startup-profile-<commit-sha>` | Phone-ready ZIP/bundle with eight APKs, two runner scripts, `START-HERE.txt` and `SHA256SUMS.txt` |

Workflow artifact retention is 14 days. Download artifacts from the Actions run, not from an assumed release URL.

A successful CI run proves that the host regression-test task and the four APK-pair builds/bundle validations passed for that commit. It does **not** prove that playback works on a physical phone, that the player threading bug is fixed at runtime, that an iOS build passed, or that a handset startup benchmark was actually executed. The phone bundle is used later for the separate device-run procedure described in its `START-HERE.txt`.

For run #138 specifically, the separate **Build Test IPA** run #101 was cancelled. Do not report that iOS passed as part of Android run #138.

## 6. Future build procedure — use this checklist every time

### Before starting

- [ ] Confirm the user explicitly authorised starting a build. A request to inspect/fix code or document a workflow is not by itself authorisation to launch CI.
- [ ] Confirm repository `jeff25steve/NuvioMobile-Enhanced-test` and target branch `main-build-testing`. Do not substitute `enhanced`, `main`, or a `ci/*` branch.
- [ ] Read this runbook, the current workflow file and the current Gradle preflight.
- [ ] Check branch HEAD, working tree/branch changes available through the authorised repository tools, recent commits and open Actions runs before making another build.
- [ ] Inspect the full failed job log from the earliest failing step. Do not assume every red workflow has a Gradle problem.
- [ ] Fix and review all known source/configuration errors before spending another CI run. For Kotlin compile failures, resolve the compiler's exact file, line and expected/actual types first.
- [ ] Check current `gradle-wrapper.properties`, `gradle/libs.versions.toml` and the workflow's Java/Gradle assertions. If they differ from the historical tuple above, document the difference and use the branch's actual validated configuration; do not force the historical versions onto newer code.
- [ ] Confirm there is no already-running workflow for the exact commit. Avoid redundant/concurrent pushes that cancel or duplicate builds.

### Start the Android workflow

**Known-good route:** Push the reviewed commit to `main-build-testing`; its push trigger includes this workflow. Verify Actions picked up exactly the commit SHA that was intended.

**Manual route:** When specifically choosing to dispatch through GitHub Actions, open **Actions → Android Startup Profile Benchmark CI Branch Runner → Run workflow**, choose `main-build-testing`, and start it. The workflow defines `workflow_dispatch`, but reference run #138 was triggered by push, not by manual dispatch.

Do not use the separate `ci/main-build-testing-20261124` or `ci/main-build-testing-preflight-20261126` branches for the normal build unless the user explicitly requests CI-branch testing. If any CI branch is used, first bring it up to the latest intended `main-build-testing` commit and let the workflow's freshness guard prove it; never bypass that guard.

### After starting

- [ ] Open the run and check its `head_branch`, `head_sha`, workflow path, event, run status and job list.
- [ ] Confirm the source-freshness guard passes before Gradle starts.
- [ ] Confirm Java/Gradle verification passes before interpreting later failures.
- [ ] Follow the ordered stages in section 3. Stop on the first failed step and inspect that step's full logs plus uploaded diagnostics.
- [ ] Do not rerun the same failed commit without changing the proven cause or explaining why a rerun is appropriate (for example, a documented transient infrastructure failure).
- [ ] Confirm host tests, all four APK groups, bundle assembly, checksums and artifact uploads all passed.
- [ ] Report the exact run URL, branch, commit, successful/failed steps and artifact names. Never say "build passed" if only an earlier stage passed.

## 7. Failure diagnosis rules

1. **Checkout/source guard failure:** inspect branch selection and whether the commit contains the latest `main-build-testing` HEAD. Do not touch Gradle.
2. **Java/Gradle verification failure:** compare the actual setup action output, wrapper distribution URL/checksum and workflow assertion. Resolve version drift explicitly; do not arbitrarily upgrade both.
3. **Script validation failure:** run/fix the named shell test or syntax check, then revalidate that exact script.
4. **Android host test/compile failure:** read the first compiler/test failure and full trace. Fix the responsible code and add/repair a focused regression test where appropriate. Re-run the required tests before launching the expensive package stages.
5. **Rust/NDK failure:** check installed target, pinned NDK, `cargo-ndk` version, Cargo lock/config and the relevant native build log. Do not change Kotlin/Gradle to mask a native-toolchain error.
6. **APK packaging/validation failure:** inspect missing APK paths, application IDs, debuggable/profileable flags, mapping files, native ABIs and packaged profile checks. Do not upload an unverified bundle.
7. **Runner resource/infrastructure failure:** use the captured memory/disk/process snapshots and per-group timing/logs to prove whether the cause was resource exhaustion or an external failure before changing heap limits, worker count or caches.

The workflow should be run as a single, ordered evidence-gathering pipeline, not as repeated blind retries.

## 8. Source links

- [Successful GitHub Actions run #138](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/45879964256)
- [Exact workflow file at the successful commit](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/blob/487076e34e7064926dde182ac4279fab69c4cc80/.github/workflows/android-startup-profile-benchmark-ci.yml)
- [Gradle wrapper at the successful commit](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/blob/487076e34e7064926dde182ac4279fab69c4cc80/gradle/wrapper/gradle-wrapper.properties)
- [Version catalog at the successful commit](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/blob/487076e34e7064926dde182ac4279fab69c4cc80/gradle/libs.versions.toml)
- [Current Android startup CI workflow](../../.github/workflows/android-startup-profile-benchmark-ci.yml)
- [Gradle build preflight and compatibility notes](gradle-build-preflight-2026-11-26.md)
