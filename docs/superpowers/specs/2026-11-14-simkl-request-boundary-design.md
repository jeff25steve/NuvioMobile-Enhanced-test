# Simkl Request Boundary Hardening — Design

**Date:** 14 November 2026  
**Base:** `main-build-testing` at `781937f9c8e5f9897ce6e229dde50b27ffac848b`

## Intent

Harden two boundaries in Simkl API requests: server-directed retry timing and the legacy PIN polling path segment.

## Goals

- Ensure numeric `Retry-After` values cannot overflow during seconds-to-milliseconds conversion and returned delay remains capped at the existing 60,000 ms limit.
- Keep a server-provided legacy PIN `user_code` inside one encoded URL path segment.
- Add pure common tests for both observable URL and retry-delay contracts.

## Non-goals

- Do not add HTTP-date parsing for `Retry-After` in this change.
- Do not change retry count, exponential schedule, request rates, auth flow, or API route.
- Do not modify workflow files, dispatch CI, run Gradle tests, or build APKs.

## Approaches

1. **Retry-After:** multiplying first and capping later is unsafe for large Long values. Chosen approach: clamp parsed seconds to 60 before multiplying, then cap again after jitter.
2. **PIN route:** encoding the full path would damage fixed slash separators; string interpolation without encoding permits reserved delimiters. Chosen approach: use Ktor 3.4.1's `encodeURLPathPart` only on the dynamic user-code segment.
3. **Broader URL-builder refactor:** deferred because all other request paths are fixed constants in this flow and a generic route rewrite would broaden compatibility risk.

## Constraints

- Preserve Ktor 3.4.1 and existing `buildSimklApiUrl` behavior.
- Keep all retries within the existing 60,000 ms cap.
- Do not claim test or build success without execution evidence.
