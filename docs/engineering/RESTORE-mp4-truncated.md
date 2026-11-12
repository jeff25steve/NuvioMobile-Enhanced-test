# Restore note — truncated `mp4.rs` push (12 November 2026)

## What happened
A partial push left `native/container/src/mp4.rs` incomplete (missing `video_keyframes` and below).
The last known-good content is commit `ff728994d9e086e93b370a350edd30b418045f05`.

## Restore immediately

```bash
git fetch origin
git checkout ff728994d9e086e93b370a350edd30b418045f05 -- native/container/src/mp4.rs
git commit -m "fix(native): restore mp4.rs after truncated push [skip ci]"
git push origin main-build-testing
```

## Improvement ready to re-apply

After restore, the sample-size / chunk-offset hardening lives in the agent workspace as:
- Local: prepared complete `mp4.rs` (41 398 bytes) with `parse_sample_sizes`, `parse_chunk_offsets`, call-site switch, and regression tests
- Diff vs good: ~7 KB unified patch

Re-apply only after the good file is restored and verified to compile structurally (`fn video_keyframes` present).

## Do not build until restored
The truncated file will fail `:androidApp:buildRust` / native linking.
