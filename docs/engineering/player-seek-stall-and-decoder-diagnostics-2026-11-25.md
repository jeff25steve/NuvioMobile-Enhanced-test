# ExoPlayer seek-stall and per-track failure diagnostics

Date: 2026-11-25
Branch: `main-build-testing`
Baseline: `723399b4c35289a3552525012e5f1bdc5dca44eb`

## Scope

This increment adds diagnostic-only visibility for two gaps identified after the video frame-output watchdog work:

- prolonged buffering immediately following an explicit seek;
- the exact renderer track and media format associated with a fatal renderer failure or a non-fatal codec callback.

It does not alter decoder priority, renderer ordering, source fallback, engine selection, or retry policy.

## Seek observation

`ExoSeekStallWatchdog` starts only after Media3 reports `DISCONTINUITY_REASON_SEEK`. It reports once after 15 seconds of uninterrupted `STATE_BUFFERING` while playWhenReady is true and playback is not suppressed. Pause and suppression time is excluded. READY, IDLE and ENDED clear the pending observation. A newer seek replaces the previous target. The diagnostic is explicitly labelled as prolonged buffering, not proof of a decoder deadlock or failed seek.

## Decoder and format context

Fatal ExoPlayer renderer failures already expose renderer identity and `rendererFormat`. The diagnostic context now also classifies the renderer track from the format MIME type and includes the existing detailed track-format summary (codec, resolution/frame rate or audio channels/sample rate). Non-fatal audio/video codec callbacks now log the corresponding active format alongside the initialized decoder name. These callbacks remain observational and never trigger a retry.

## Verification boundary

Added host tests cover timeout boundaries, pause/suppression exclusion, terminal-state clearing, superseding seeks, stale attempts and invalid positions. No build, test task or CI workflow was started, per instruction. Compilation and test execution remain unverified until explicitly authorized.
