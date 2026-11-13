# Authentication session reliability checkpoint

Updated: 2026-11-13

## Scope

Branch: `main-build-testing`

This checkpoint continues the Supabase authentication/session work in `AuthRepository`. It is deliberately limited to credential cleanup during server switches and classification of invalid remote sessions. No build, test task, CI workflow, release, or merge was started.

## Completed improvement cycles

### 1. Server-switch cancellation does not skip the second credential clear

Root cause: `runServerSwitchCleanup` used `runCatching` around two sequential suspending credential-clear operations. Catching a `CancellationException` does not reactivate the coroutine. A subsequent suspending remote-session clear can therefore immediately fail due to the already-cancelled caller, leaving the old Supabase session uncleared.

Change:
- Execute the anonymous-identity clear and remote Supabase-session clear together inside `withContext(NonCancellable)`.
- Preserve the existing policy: propagate cancellation after credential cleanup, and do not wipe local account data when cancellation or either credential clear fails.
- Added a regression test that cancels while the anonymous clear is suspended and verifies the remote credential clear completes while local data cleanup remains skipped.

Relevant files:
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/auth/AuthRepository.kt`
- `composeApp/src/commonTest/kotlin/com/nuvio/app/core/auth/AuthServerSwitchCleanupTest.kt`

### 2. Do not invalidate a user session for a bad project API key

Root cause: `isInvalidRemoteSessionResponse` treated every HTTP 401 as proof that the user's Supabase session was invalid (except PostgREST SQLSTATE 42501). A project/API-key configuration failure can also return 401; clearing the user's persisted session in that case is destructive and does not repair the backend configuration.

Change:
- Require explicit invalid JWT/session/user evidence for 400/401/403/404 responses instead of treating status 401 alone as sufficient.
- Explicitly exclude `invalid_api_key`, `Invalid API key`, and `API key is required`.
- Preserve the existing 42501/RLS exclusion and message-based invalid-session detection, including explicit 403 session-not-found cases.
- Added focused classifier tests for RLS denial, invalid API key, explicit 403 session-not-found, and malformed JWT.

Relevant files:
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/auth/AuthSessionErrors.kt`
- `composeApp/src/commonTest/kotlin/com/nuvio/app/core/auth/AuthSessionErrorClassificationTest.kt`



### 3. Use one cause-aware invalid-session classifier

Root cause: `AuthRepository` duplicated the classifier already provided by `AuthSessionErrors.kt`. The duplicate only assembled the outer exception message and a discovered REST exception's fields; the shared helper traverses the complete cause chain. Wrapped SDK/network errors could therefore be classified differently depending on the call path.

Change:
- Replaced the repository-local classifier with `Throwable.isInvalidSessionError()`.
- Exposed the existing cause-chain message collector internally and added a focused test proving nested causes are retained.
- Removed obsolete REST exception imports and duplicate classification code.

Relevant files:
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/auth/AuthRepository.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/auth/AuthSessionErrors.kt`
- `composeApp/src/commonTest/kotlin/com/nuvio/app/core/auth/AuthSessionErrorsTest.kt`

### 4. Finish invalid-session cleanup after caller cancellation

Root cause: `clearLocalSessionAfterRemoteInvalidation` used `runCatching` around suspending remote-session clearing and local account cleanup. If its caller was cancelled during the remote clear, the following suspending local wipe could immediately throw cancellation and be swallowed, leaving account-scoped data behind after a confirmed invalid session.

Change:
- Added `runInvalidSessionCleanup`, which attempts anonymous identity removal, remote session clearing, and local cleanup in a `NonCancellable` section.
- It still checks the originating session generation before each step, so a replacement identity is not wiped.
- Ordinary cleanup failures are returned for logging; caller cancellation and explicit cancellation failures are propagated after the eligible cleanup sequence finishes.
- Added regression coverage for cancellation during remote clear, identity replacement, and local cleanup despite an ordinary remote-clear failure.

Relevant files:
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/auth/AuthRepository.kt`
- `composeApp/src/commonTest/kotlin/com/nuvio/app/core/auth/InvalidSessionCleanupTest.kt`



### 5. Preserve cancellation while aggregating local account cleanup failures

Root cause: `runAccountCleanupSteps` deliberately catches each `Exception` so later cleanup steps still run. Kotlin's `CancellationException` is also an `Exception`, so the final aggregation previously wrapped an explicit cancellation signal in an ordinary `IllegalStateException`. That breaks the coroutine cancellation contract even though the remaining cleanup steps were attempted.

Change:
- Added `throwAccountCleanupFailures` to rethrow the first captured `CancellationException` after all eligible steps and finalizers have been attempted.
- Ordinary cleanup failures are attached as suppressed exceptions to that cancellation; without cancellation, the existing aggregate `IllegalStateException` behavior is preserved.
- Added a regression test that verifies later cleanup still runs and the original cancellation is propagated with the later failure retained.

Relevant files:
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/storage/LocalAccountDataCleaner.kt`
- `composeApp/src/androidHostTest/kotlin/com/nuvio/app/core/storage/LocalAccountDataCleanerTest.kt`

### 6. Roll back add-on cleanup gate acquisition when its callback fails

Root cause: `AddonProfileOperationGate.beginAccountCleanup` increments `accountCleanupDepth` before invoking the supplied invalidation/cancellation callback. If that callback throws, the depth was never decremented, permanently preventing future profile-scoped add-on operations from acquiring a current operation.

Change:
- Roll back the depth and advance the generation again if the begin callback throws.
- Preserve any outer/nested cleanup lease by decrementing only the acquisition that failed.
- Added regression coverage for both a failed first acquisition and a failed nested acquisition.

Relevant files:
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/addons/AddonProfileOperationGate.kt`
- `composeApp/src/commonTest/kotlin/com/nuvio/app/features/addons/AddonProfileOperationGateTest.kt`

## Evidence and implementation constraints

- Supabase dependency in `gradle/libs.versions.toml`: `3.4.1`; Ktor: `3.4.1`; kotlinx-coroutines: `1.10.2`.
- Supabase Kotlin 3.4.1 release notes and SDK documentation were consulted. The SDK distinguishes terminal auth refresh errors from network/internal-server refresh failures; network/5xx refresh failures are retried rather than automatically clearing the stored session.
- The Supabase Kotlin docs index did not provide a pinned 3.4.1 documentation snapshot; the exact release tag and upstream source references were checked separately.
- Coroutines guidance confirms that suspending cleanup in a cancelled coroutine requires `withContext(NonCancellable)`; cancellation is still observed when leaving that context. Kotlin's official cancellation guidance also explicitly warns that catching `CancellationException` without rethrowing it breaks cancellation propagation, supporting cycle 5's aggregate-then-rethrow behavior.
- Regression tests were added/updated remotely but were **not executed**, because builds and CI were explicitly not authorised. No passing-test or build claim is made. Context7 documentation for kotlinx-coroutines 1.10.1 (closest indexed release; project pins 1.10.2) confirms that suspending cleanup in a cancelled coroutine should use `withContext(NonCancellable)`. Context7's Supabase Kotlin 3.4.1 index did not contain a directly matching exception-hierarchy page; upstream SDK and Supabase error-handling references were checked through Exa.

## Follow-up review

- Review callers of `beginServerSwitch` / `prepareForServerSwitch` to confirm every backend replacement holds the shared session-reset lease before old-client cleanup.
- Review `AuthSessionOperationGate` handoff/cancellation behavior against its existing tests; do not change its mutex semantics without a dedicated reproducer.
- Before authorising a build, run the focused auth tests (`AuthServerSwitchCleanupTest`, `AuthSessionErrorClassificationTest`, `AuthSessionErrorsTest`, and `InvalidSessionCleanupTest`), then the focused storage/add-on tests (`LocalAccountDataCleanerTest` and `AddonProfileOperationGateTest`), followed by the project's full test suite. Do not start CI automatically.


## Latest remote integration status

- All changes in this checkpoint are committed on `main-build-testing`.
- The latest branch comparison against `enhanced` reports **0 commits behind**; no sync or merge was needed.
- Latest implementation commits:
  - `cf919b60c77b0e41156a6d92ee7dc4dbf81212ba` — shared cause-aware session classification.
  - `df8d839f3f6d5cf61a5641481a9d26d6424ff29c` — cancellation-safe invalid-session cleanup.
- Regression-test commits:
  - `9c39e881f44b79b750bf9ce1dfbc48c6482173b4`
  - `dc88688138a5526c0086cff5530a41a8e6017f5e`
- This notes update is committed separately; remote file contents were fetched again after integration.
- **Verification boundary:** source and test files were inspected remotely, but no test command, Gradle task, build, or CI workflow was run. Tests remain unverified until authorised.


## Latest continuation status (2026-11-13 16:00 UTC)

- Cycles 5 and 6 were committed on `main-build-testing`:
  - `1fe6524313f7d4bcdacba5e397a2f06c453b2fc4` — preserve cancellation after account cleanup steps.
  - `83ead13bd6d6d3b5b885b78311047d2e71020fbc` — roll back failed add-on cleanup gate acquisition.
- Test-first commits:
  - `5ebd6bf2fd964a641b922a9a17bf0b72d8e71c75` — account-cleanup cancellation propagation.
  - `c420ef4603d0cac88cac91978effdaf00efc9868` — failed add-on gate acquisition and nested lease preservation.
- Test formatting-only follow-ups:
  - `d58bafd3b4394bd328b370ca077c72bb7a32f212`
  - `25f989667ee00562554ec5857bfe8221ab4d7c79`
- No tests, Gradle tasks, builds, or CI workflows were executed. The test-first ordering is present in the commit sequence, but RED/GREEN execution remains unverified under the no-build/CI constraint.
- Exa research checked the official Kotlin cancellation guide and kotlinx.coroutines `NonCancellable` source; Context7's closest indexed coroutines documentation is 1.10.1 while this repository pins 1.10.2.

## Latest continuation status (2026-11-13 16:12 UTC)

- Two further cleanup-gate reliability cycles were integrated:
  - Cycle 7 — make add-on/plugin cleanup gate acquisition exception-safe as one logical lease. If plugin acquisition fails after the add-on gate was acquired, the add-on lease is rolled back. Successful acquisitions are released in reverse order, and release failures are preserved rather than preventing the second release.
  - Cycle 8 — make PluginProfileOperationGate.beginAccountCleanup roll back its own depth/generation acquisition when its callback throws, preserving any outer cleanup lease.
- Regression tests were added first:
  - 2616fb92683ca43973b3dff6762c42c25d4f628b — later plugin gate acquisition failure releases the already-acquired add-on gate; successful leases release in reverse order.
  - 821e556c4154ef2c360e3fc0c86477dd4a26081e — plugin cleanup gate first-acquisition and nested-acquisition callback failures roll back only their own depth.
- Implementations:
  - 9febc6fd885069f678c666b98179296052cbf1b9 — add combined cleanup-gate acquisition/release helper.
  - 1d0d1ecbe8632e846641a85e68d5c8c731a7b852 — rollback failed plugin cleanup gate acquisition.
  - 9429b7d0a6488907957f3d6dd18c74ad368bc434 — place gate acquisition inside the cleanup finalization scope, so a failure after local setup still triggers the applicable finalizers.
- Evidence reviewed: Kotlin coroutine resource/cancellation guidance and Kotlin try/finally semantics; Context7's closest indexed kotlinx-coroutines version is 1.10.1 (project pins 1.10.2), and AtomicFU lock documentation was checked against the project's 0.32.1 dependency. The implementation uses the repository's existing synchronous lock APIs and adds no dependencies.
- Code review checked each new commit diff and the final remote source/test files. No unrelated files were changed by these cycles.
- No tests, Gradle tasks, builds, or CI workflows were run. The added regression tests remain unexecuted until explicitly authorised.
- The branch was confirmed to be 0 commits behind enhanced (1,451 ahead) at this checkpoint; no upstream sync was required.
