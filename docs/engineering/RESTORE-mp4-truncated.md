# MP4 parser recovery — restored source and table hardening integrated (12 November 2026)

The truncated and placeholder versions of `native/container/src/mp4.rs` have been superseded by a complete parser on `main-build-testing`.

- Restored the complete parser from the last structurally complete revision, including `video_keyframes`, CTTS/CSLG/STTS handling and existing regression modules.
- Preserved the newer sample-size (`stsz`) and chunk-offset (`stco`/`co64`) validation by integrating both validators into the active `video_keyframes` path.
- Added regression coverage for fixed/variable sample-size tables, malformed lengths/flags/counts/zero sizes, 32-bit and 64-bit offsets, and invalid offset table widths.
- Structural checks verify the parser entry points, both validator call sites and the new regression module are present.

Rust tests and both platform builds still need fresh validation. This note does not claim those checks have passed.
