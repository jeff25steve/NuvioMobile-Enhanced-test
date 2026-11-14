# Simkl Request Boundary Hardening Implementation Plan

> **For agentic workers:** Executed inline against `main-build-testing`. Build and test execution was intentionally omitted because the user prohibited Gradle tests, builds, and CI.

**Goal:** Prevent overflow in Simkl retry delays and safely encode the legacy PIN user-code path segment.

**Architecture:** Keep retry parsing in `SimklApiClient.kt`, clamp the numeric seconds value before conversion, and retain the final delay cap. Add a small common helper in `SimklDeviceAuth.kt` using Ktor's path-segment encoder, and call it only from the legacy PIN polling request.

**Tech Stack:** Kotlin 2.4.10, Ktor 3.4.1, kotlinx.coroutines 1.10.2, Kotlin test.

**Spec:** `docs/superpowers/specs/2026-11-14-simkl-request-boundary-design.md`

## Global Constraints

- No Gradle tests, builds, APK tasks, or CI dispatches.
- No workflow changes.
- Preserve the retry count and 60-second maximum.
- Preserve fixed Simkl API route separators and query handling.
- HTTP-date Retry-After parsing remains out of scope.

## Review Focus

- `Long.MAX_VALUE` retry-after seconds must not overflow when converted to milliseconds.
- Jitter must not push the returned retry delay beyond 60,000 ms.
- `/`, `?`, `#`, and `%` in a PIN code must not change the route, query or fragment.
- Normal alphanumeric PIN codes must remain unchanged by path-segment encoding.

## Task 1: Retry delay overflow

**Files:**
- Modify: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/simkl/SimklApiClientTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/simkl/SimklApiClient.kt`

**Changes:**
- Add tests in `retry schedule uses exponential backoff and respects retry after` for `Long.MAX_VALUE.toString()`, with zero and maximum jitter; expected result remains `60_000L`.
- Add `SIMKL_MAX_RETRY_DELAY_MS = 60_000L`.
- Clamp the parsed seconds to `SIMKL_MAX_RETRY_DELAY_MS / 1_000L` before multiplication; preserve the final cap after jitter.

**Execution:** Tests were added before production change in commit order. Test execution is pending authorisation.

## Task 2: PIN path-segment encoding

**Files:**
- Modify: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/simkl/SimklApiClientTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/simkl/SimklDeviceAuth.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/simkl/SimklAuthRepository.kt`

**Changes:**
- Add a URL-builder regression asserting `A/B?C#D%` produces `/oauth/pin/A%2FB%3FC%23D%25` before the query.
- Add `buildSimklPinPollPath(userCode: String): String` using Ktor `encodeURLPathPart()`.
- Call the helper from the legacy PIN polling request.

**Execution:** Test was committed before production code. Test execution is pending authorisation.

## Task 3: Documentation

Update `docs/engineering/codebase-improvement-notes.md` with both changes, references, scope boundaries and the no-build verification status.

## Completion evidence

- Compare the integrated tree with the starting SHA.
- Confirm only the Simkl API/auth/test files and documentation changed.
- Confirm no workflow files changed.
- Confirm that test/build results remain explicitly unverified.
