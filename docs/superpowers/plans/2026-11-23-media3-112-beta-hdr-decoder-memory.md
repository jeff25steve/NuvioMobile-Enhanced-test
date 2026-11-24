# Media3 1.12 beta attempt: compatibility failure and stable Nuvio artifact restoration

**Status — 24 November 2026:** The 1.12.0-beta01 rollout is blocked. The active repair restores the last known successful Nuvio-patched Media3 1.8.0 artifact set and adapts the current player sources to that API. A full build remains pending explicit authorization.

## Confirmed failure chain

1. **Run #62** failed during Android Kotlin compilation after 13 minutes 56 seconds. This was not a runner timeout. The compiler reported missing Nuvio-specific APIs used by the app:
   - `DefaultRenderersFactory.setMapDV7ToHevc`
   - `FfmpegAudioRenderer.isCenterMixActive`
   - `FfmpegAudioRenderer.setCenterMixLevelDb`
   - `FfmpegAudioRenderer.setAudioOutputChannels`
   - `FfmpegAudioRenderer.setDownmixNormalizationEnabled`

2. The five local AARs on the failed path differ from the five AARs in the last known successful run. The FFmpeg decoder artifact fell from 9,198,662 bytes to 3,587,450 bytes; the generic artifact does not contain the Nuvio downmix extensions.

3. **Run #51** succeeded with Media3 1.8.0 and the Nuvio-patched AAR set at commit `71cc598e1ddbbc5736522f44a62a16973fac6761`. The same five AAR blobs were present at the run #61 checkpoint.

4. **Run #61** demonstrated the other half of the problem: restoring the 1.8.0 version and Nuvio AARs alone is insufficient while source files still use newer Media3 APIs. Its compile errors included the newer `StuckPlayerException` type, the 1.12 `Flags` registry, `Decoder.Callback`, and the newer `ElementaryStreamReader` completion methods.

5. The failed CI branch was also one commit behind `main-build-testing`. This repair branch is based directly on current `main-build-testing` head `5bf1cec323a2d621bb42561931cc2cc0d40a30fd`, so it includes the latest main changes and satisfies the workflow's source-freshness requirement.

## Artifact fingerprints

The compatibility guard locks the local AARs to the exact Git blob IDs from run #51. Current failed-path fingerprints are shown for comparison:

| Artifact | Last known-good Nuvio AAR | Failed-path AAR |
|---|---|---|
| lib-decoder-av1-release.aar | `4caa18a79339cf5b0e7560b2c8068cffda478f01` (2,888,290 bytes) | `b0ea68d9e0cdd46bc167a343dcfa94732a3adfb8` (2,305,415 bytes) |
| lib-decoder-ffmpeg-release.aar | `75ec0a868a99b87d3ee3e1ccd4d191c3a49560e4` (9,198,662 bytes) | `b8ee68452bad7fd895657c5f3a9840d566622946` (3,587,450 bytes) |
| lib-decoder-mpegh-release.aar | `e2b019207b80c838a4dbb73976d8120ff101d18f` (1,353,272 bytes) | `e808e5f6bf44301f184aacb114888f65508bc1ef` (1,636,552 bytes) |
| lib-exoplayer-release.aar | `f55ad58cae1242d51036cf980a2332c50f620b50` (1,578,653 bytes) | `61377b0160ca93af7113e8e2cf42eb535c9c00b6` (1,749,272 bytes) |
| lib-ui-release.aar | `073f9b6be8e8cc2f95a6934dfff5c232f61451af` (432,116 bytes) | `32f2a8f52e4f0b7ecbffb3dc41e2da39b29eb0da` (340,753 bytes) |

## Decision

- Keep `androidx-media3` pinned to **1.8.0** while the repository depends on these Nuvio-specific binary extensions.
- Restore all five AARs from the last known successful artifact set as one coherent group.
- Do not substitute generic Just Player/Moneytoo AARs for Nuvio-patched artifacts.
- Defer Media3 1.12 beta features, including the process-wide renderer scheduling flag, until the Nuvio custom AARs are rebuilt and verified against the same newer Media3 version.
- Preserve the current Dolby Vision Profile 8 selection logic, libmpv scaling-filter propagation, frame-rate-state updates, and custom audio controls on the compatible 1.8 API surface.

## Source compatibility work

- Use the 1.8 `DefaultLoadControl.Builder` methods and the 1.8 `LoadControl` signatures. Local and streaming buffer start/rebuffer thresholds remain source-specific through the selected player configuration.
- Use the 1.8 `DefaultTrackSelector.selectAllTracks` and `invalidate()` APIs, retaining the null-safe display-listener registration.
- Use the 1.8 two-argument `MediaCodecVideoRenderer.onPositionReset`.
- Use the 1.8 `MediaCodecUtil.getDecoderInfosSortedByFormatSupport(decoderInfos, format)` overload.
- Use `androidx.media3.common.util.Consumer`.
- Use `ElementaryStreamReader.packetFinished(isEndOfInput)`; clear PGS pending data and flush TrueHD only when end-of-input is signalled.
- Remove the post-1.8 `Decoder.Callback` implementation from the software decoder. Media3 1.8's `DecoderVideoRenderer` polls decoder input/output queues during render passes; the worker continues to signal its own lock.
- Detect `StuckPlayerException` reflectively and preserve the three previously recognized stuck types without linking against a class absent from Media3 1.8.
- Keep the renderer-duration scheduling call site as an explicit no-op on 1.8; do not reference the unavailable 1.12 `Flags` registry.

## Early compatibility gate

The existing phone-benchmark contract suite now invokes `scripts/tests/test-media3-nuvio-artifact-compatibility.sh` before any Gradle task. It checks the shared Media3 version alias, minimum SDK, shared Media3 module versions, local AAR wiring, all five exact Nuvio AAR Git blob fingerprints, and the known 1.8 API signatures in affected player files.

This would have caught the generic-AAR substitution before Gradle spent another 14 minutes compiling.

## Implementation status

- [x] Restore the Media3 1.8.0 version alias and the five known-good Nuvio AAR blobs.
- [x] Port the affected Android player source files to the 1.8 API surface while retaining the current main-branch fixes.
- [x] Add the pre-Gradle compatibility guard and wire it into the existing CI contract step.
- [x] Update this plan, the design spec, and the existing engineering notes.
- [ ] Run one controlled Android build and inspect the full result after explicit authorization.

## Verification boundary

No Gradle build, workflow run, or CI job was started during this repair. Source/API checks and remote commit verification are static checks only; they are not a claim that compilation has passed.
