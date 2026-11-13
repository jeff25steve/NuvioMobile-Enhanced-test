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

## Evidence and implementation constraints

- Supabase dependency in `gradle/libs.versions.toml`: `3.4.1`; Ktor: `3.4.1`; kotlinx-coroutines: `1.10.2`.
- Supabase Kotlin 3.4.1 release notes and SDK documentation were consulted. The SDK distinguishes terminal auth refresh errors from network/internal-server refresh failures; network/5xx refresh failures are retried rather than automatically clearing the stored session.
- The Supabase Kotlin docs index did not provide a pinned 3.4.1 documentation snapshot; the exact release tag and upstream source references were checked separately.
- Coroutines guidance confirms that suspending cleanup in a cancelled coroutine requires `withContext(NonCancellable)`; cancellation is still observed when leaving that context.
- Regression tests were added/updated remotely but were **not executed**, because builds and CI were explicitly not authorised. No passing-test or build claim is made.

## Follow-up review

- Review callers of `beginServerSwitch` / `prepareForServerSwitch` to confirm every backend replacement holds the shared session-reset lease before old-client cleanup.
- Review `AuthSessionOperationGate` handoff/cancellation behavior against its existing tests; do not change its mutex semantics without a dedicated reproducer.
- Before authorising a build, run the focused common test target for `AuthServerSwitchCleanupTest` and `AuthSessionErrorClassificationTest`, then the project's full test suite. Do not start CI automatically.
