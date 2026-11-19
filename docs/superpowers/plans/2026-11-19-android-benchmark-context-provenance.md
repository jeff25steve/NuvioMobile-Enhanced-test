# Android benchmark context provenance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bind benchmark eligibility and human-readable device metadata to AndroidX BenchmarkData's actual root `context` object.

**Architecture:** Add a small quote/escape-aware AWK helper that extracts a direct child JSON object. Reuse it in the phone runner's API validation and summary writer, with fake-device fixtures for root-level metadata decoys.

**Tech Stack:** POSIX shell, AWK, AndroidX BenchmarkData 1.5.0 JSON; no new dependency.

**Spec:** `docs/superpowers/specs/2026-11-19-android-benchmark-context-provenance-design.md`

## Global Constraints

- Keep the runner POSIX-shell/AWK-only; do not add jq, Python, root requirements or dependencies.
- Keep the existing three comparison arms, ten iterations, TTID/TTFD/ART metrics, Perfetto traces, APK variants and measured app journey unchanged.
- Do not modify app source, Java benchmark source, Gradle files, dependencies or workflows.
- Do not run Gradle, build, CI, instrumentation, LADB or a physical-device benchmark.
- Do not claim a measured startup improvement.

## Review Focus

- A root-level `build.version.sdk` decoy must not satisfy the root context SDK contract.
- A missing or non-object `context`, `build` or `version` must fail closed.
- Braces, escaped quotes and backslashes inside JSON strings must not corrupt object-boundary detection.
- Root-level model/CPU/memory/ART decoys must never override context values in the summary.
- Valid AndroidX 1.5.0 context data and existing missing-context-SDK fixtures must retain their current behavior.

---

### Task 1: Make SDK eligibility follow the exact JSON parent path

**Files:**
- Modify: `scripts/nuvio-phone-benchmark.sh`
- Test: `scripts/tests/test-nuvio-phone-benchmark.sh`
- Test: `scripts/tests/test-android-startup-metric-contract.sh`

**Interfaces:**
- Add AWK helper `json_member_object(source, field)`, returning the direct child object text for `field` or an empty string. It must ignore nested members and braces inside escaped JSON strings.
- Use the helper to resolve root `context` → direct `build` → direct `version`; read `sdk` only from that version object.
- Add fake fixture `misleading-global-build`: root `context` is empty while a root sibling `build.version.sdk` is API 35. The runner must reject it with the existing context SDK diagnostic.
- Keep the existing `missing-context-sdk` and `misleading-global-sdk` fixtures.

- [x] Add the fake malformed-context fixtures and include them in the invalid-fixture allowlist (root build/SDK decoys and non-object context/build/version).
- [x] Add static-contract assertions for json_member_object and all three direct path extractions.
- [x] Implement and use the quote/escape-aware helper in the validator AWK program.
- [x] Re-fetch the modified sources and inspect the exact fixture and validator changes.

### Task 2: Make summary device metadata context-scoped

**Files:**
- Modify: `scripts/nuvio-phone-benchmark.sh`
- Test: `scripts/tests/test-nuvio-phone-benchmark.sh`
- Test: `scripts/tests/test-android-startup-metric-contract.sh`

**Interfaces:**
- Resolve `context`, `context.build` and `context.build.version` with the same `json_member_object` helper in `write_macrobenchmark_summary()`.
- Read `model` from `context.build`, `sdk` from `context.build.version`, and CPU/memory/ART fields from `context`.
- Add a valid fixture variant with contradictory root-level device metadata and assert the summary retains `Fixture phone`, API 35, 8 cores, 2,800,000,000 Hz, 12,884,901,888 bytes, ART 123456789 and sustained performance false, with no decoy values.

- [x] Add root metadata decoys to a valid fake JSON variant and wire it into the existing combined-run measurement fixture.
- [x] Assert only context-owned values appear in the benchmark summary.
- [x] Replace global summary metadata lookups with direct context/build/version object lookups.
- [x] Re-fetch and review the edited summary helper and its regression assertions.

### Task 3: Engineering record and remote verification

**Files:**
- Modify: `docs/engineering/codebase-improvement-notes.md`
- Modify: `docs/engineering/android-startup-benchmark-phone-fix-2026-11-15.md`
- Update: this plan.

- [x] Record both concrete defects, AndroidX 1.5.0 JSON evidence, fixes, regression coverage and verification limits.
- [x] Re-fetch all changed paths and inspect the final remote file contents and branch head.
- [x] Confirm the compare view contains only the phone runner, regression/static-contract scripts, benchmark notes and this plan/spec.
- [x] Confirm no workflow, app source, benchmark workload, Gradle configuration or dependency changed.
- [x] Confirm no build, CI, instrumentation, LADB or device benchmark was started.


## Execution record — 19 November 2026

- Remote implementation commits are on main-build-testing. The branch started at 07e7d1fa8c8b979eb110f63b0acb08788f5aa3fe and currently contains the two scoped benchmark-integrity cycles.
- The prior validator's root-build false acceptance was reproduced in isolation. The direct-object AWK extractor was exercised with valid context data, root/nested decoys, escaped quotes/braces, and missing/non-object context/build/version inputs.
- The fake JSON injection fragment was checked independently; the complete repository fixture script was not executed.
- **Not verified:** the full phone benchmark regression script and static metric-contract shell script were not run because there is no local repository checkout in this session. This prevents marking the two implementation tasks fully complete under the test-driven-development completion contract.
- **Final review:** self-review (no subagent tool is available). Remote changed-file comparison and source-level checks were performed; an independent fresh reviewer and full script run remain outstanding.
- No build, CI, device, instrumentation or LADB operation was started.
