# MP4 cslg and stts Validation — Implementation Plan

> **For agentic workers:** Use the Superpowers test-driven-development and verification-before-completion workflows. Implementation is intentionally limited to the native MP4/MOV parser.

**Goal:** Reconstruct presentation timestamps using an explicit `cslg` shift and validate complete decode-time tables without changing decode-time or edit-list semantics.

**Architecture:** Parse `cslg` as a signed FullBox (32-bit fields in version 0, 64-bit in version 1) and add only its `compositionToDTSShift` to the presentation-time calculation. Parse `stts` into validated runs, require exact sample coverage, retain its checked duration sum, and use that sum when `mdhd.duration` is the all-ones unknown sentinel.

**Tech Stack:** Existing Rust implementation in `native/container/src/mp4.rs`; no new dependencies or public API changes.

**Spec:** Existing checkpoint and constraints in `docs/engineering/codebase-improvement-notes.md`.

## Global Constraints

- Do not change public parser APIs, track selection, keyframe ordering, CTTS version handling, or byte offsets.
- Keep decode time (`DTS`) independent from the shifted composition/presentation axis (`CTS`).
- Do not guess a shift when `cslg` is absent.
- Do not add edit-list interpretation in this batch.
- Do not run builds, build-triggering tests, or CI without explicit authorisation.

## Review Focus

- Signed version-0 and version-1 `cslg` field decoding.
- Negative `ctts` offsets combined with a positive `cslg` shift, including overflow-safe arithmetic.
- Malformed `cslg` headers, flags, lengths, or inverted metadata bounds.
- `stts` run/sample coverage, zero sample counts, and zero delta only for a singleton final sample.
- Unknown `mdhd.duration` sentinel with a complete `stts` duration fallback.

## Task 1: Preserve presentation timestamps and validate media decode time

**Files:**
- Modify: `native/container/src/mp4.rs`
- Modify: `docs/engineering/codebase-improvement-notes.md`

**Interfaces:**
- Add private `parse_composition_to_decode(Option<&[u8]>) -> Result<Option<i64>>`.
- Add private `parse_decode_time_table(&[u8], usize) -> Result<(Vec<(u64, u64)>, u64)>`.
- Extend private timestamp calculation to accept both CTTS offset and CSLG shift while leaving the DTS accumulator unchanged.

- [x] Add tests using the published MPEG negative-CTSO CSLG sample values, version-1 64-bit boundaries, malformed CSLG data, and unchanged no-CSLG timestamps.
- [x] Add STTS tests for exact sample coverage, checked duration summation, malformed lengths/flags/version, zero counts, and last-sample zero duration.
- [x] Implement signed CSLG parsing and apply only its explicit shift to presentation timestamps.
- [x] Replace lazy raw STTS reads with validated runs; calculate fallback duration from the complete STTS sum.
- [x] Treat all-ones MDHD duration as unknown for both versions, validate the versioned media-header shape, and fall back to STTS duration.
- [x] Review the full diff and update the engineering checkpoint.

**Verification boundary:** Test source was added first, but execution was withheld because the project instruction explicitly prohibits build-triggering tests and no test/build/CI task is authorised. Static review and offline inspection of the existing bundled MP4 fixture are the permitted checks.
