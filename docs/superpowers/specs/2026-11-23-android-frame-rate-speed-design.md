# Android manual frame-rate matching follows playback speed

**Date:** 23 November 2026  
**Target:** main-build-testing  
**Baseline:** a9c2a9c1fa7ecd72affc5edb96484cb2105cd038

## Intent

Correct the Android player's explicit non-seamless frame-rate matching path so the display hint represents the actual playback cadence when playback speed differs from 1.0x.

## Evidence and current behaviour

The app already respects Android's Match content frame rate preference. For the user's explicit Always preference, it disables Media3's seamless-only Surface request while the custom controller is eligible, then calls Surface.setFrameRate(..., CHANGE_FRAME_RATE_ALWAYS).

The custom controller previously received only Format.frameRate. AndroidX Media3's VideoFrameReleaseHelper tracks playback speed and sets the Surface rate to surfaceMediaFrameRate * playbackSpeed; it refreshes this request when speed changes. Nuvio's manual path did neither, so at 24 fps and 1.5x it could keep requesting 24 fps rather than 36 fps.

## Goals

- Calculate the manual Surface frame-rate request using source frame rate multiplied by current playback speed.
- Refresh the request immediately when Media3 reports a playback-parameter change.
- Clear invalid, unknown or overflowed values rather than retaining a stale rate.
- Preserve existing Android user-preference, long-VOD/live eligibility and Surface lifecycle behaviour.

## Non-goals

- Do not force a refresh rate globally or change the Android system preference.
- Do not change Media3's default seamless-only behaviour.
- Do not alter libmpv, HDR/Dolby Vision, decoder selection, renderer choice, track selection, buffering or dependencies.
- Do not claim that Android will switch to the requested display mode; the API remains a platform hint.

## Approaches considered

1. Leave Media3's default strategy in charge: safest for seamless-only mode, but it cannot provide the app-owned CHANGE_FRAME_RATE_ALWAYS behaviour the user explicitly enabled.
2. Keep the manual controller but mirror Media3's playback-rate calculation: selected. It is the smallest correction, aligns with upstream Media3 semantics and retains the existing opt-in gate.
3. Replace the controller with a new display-mode manager: rejected because it duplicates platform policy, adds lifecycle complexity and is unnecessary for this defect.

## Implementation decisions

- Add a pure resolver that returns mediaFrameRate * playbackSpeed only when both inputs and the product are finite and positive; otherwise return 0f.
- Refresh the controller from the shared updateAlwaysFrameRateState path using the current ExoPlayer playback speed.
- Call that shared state update from onPlaybackParametersChanged.
- Add host regression tests for normal, fractional, slow-motion, invalid and overflow inputs.
- Keep all changes Android-only.

## Verification

The user explicitly prohibited builds and CI. Therefore host tests are authored but must remain unexecuted in this cycle. Static source review must verify the exact wiring; physical-device validation remains outstanding.
