# Media3 artifact compatibility boundary — Nuvio custom player stack

## Intent

Keep the Nuvio Android player on a coherent, buildable Media3 dependency set while retaining the custom renderer, Dolby Vision, and FFmpeg audio features that the application actually uses. The current safe baseline is Media3 1.8.0 plus the exact Nuvio-patched AAR set proven by CI run #51.

The attempted Media3 1.12.0-beta01 rollout is **deferred**, not considered a valid dependency-only upgrade.

## Chosen artifact boundary

- All Maven Media3 modules resolve through the single `androidx-media3` version-catalog alias, currently `1.8.0`.
- `media3-exoplayer` and `media3-ui` are supplied by the repository's local Nuvio AARs, not generic Maven artifacts.
- The five local AARs are treated as one indivisible compatibility set. Their exact Git blob fingerprints are enforced by `test-media3-nuvio-artifact-compatibility.sh`.
- The local AARs must not be replaced by generic Just Player/Moneytoo artifacts unless the replacement has been rebuilt with and verified to contain the Nuvio-specific APIs used by the app.

## Required Nuvio extensions

The application relies on APIs not present in the generic artifacts used by failed run #62:

- `DefaultRenderersFactory.setMapDV7ToHevc`
- FFmpeg audio center-mix state and level controls
- FFmpeg output-channel selection
- FFmpeg downmix normalization

These APIs are part of the app's actual playback behavior, not optional source decorations. The build must use artifacts that contain them.

## Media3 1.8 source compatibility

The Android player implementation follows the 1.8 API surface:

- `DefaultLoadControl.Builder.setBufferDurationsMs` and `setPrioritizeTimeOverSizeThresholds`.
- `LoadControl.getAllocator()` and the 1.8 preloading method signatures.
- The 1.8 `DefaultTrackSelector.selectAllTracks` return contract and parameterless `invalidate()`.
- Two-argument `MediaCodecVideoRenderer.onPositionReset`.
- Two-argument `MediaCodecUtil.getDecoderInfosSortedByFormatSupport`.
- `androidx.media3.common.util.Consumer`.
- `ElementaryStreamReader.packetFinished(isEndOfInput)`.
- Synchronous `Decoder` queue polling: Media3 1.8 does not provide `Decoder.Callback`; `DecoderVideoRenderer` polls the decoder queues during render passes.

Newer `StuckPlayerException` detection is kept reflective, preserving the three previously classified stuck types without a compile-time reference to a class missing from 1.8.

The 1.12 process-wide renderer `Flags` scheduling toggle is intentionally not referenced. The call site remains a documented no-op until a compatible Nuvio artifact set is built for a newer Media3 version.

## Buffering behavior

The 1.8 builder exposes one set of buffer durations per player instance. The player already knows whether a source is local or streaming, so it selects the corresponding values when constructing the load control:

- local playback keeps the 1.12 local defaults (1,000 ms start and 1,000 ms rebuffer);
- streaming keeps the 1.12 streaming defaults (1,000 ms start and 2,000 ms rebuffer);
- custom buffer settings remain applied as configured;
- native-memory streaming retains its explicit large-buffer configuration.

This preserves source-specific thresholds without calling 1.12-only builder methods.

## Upgrade gate for Media3 1.12 or newer

Do not upgrade the catalog version independently. A future upgrade must:

1. Rebuild every Nuvio-specific AAR against the exact target Media3 version from reviewed source.
2. Verify the custom Dolby Vision mapping and FFmpeg downmix APIs exist in the resulting artifacts.
3. Keep all Maven Media3 modules and local AARs on the same compatible API baseline.
4. Update the source compatibility layer and static guard together.
5. Run the full Android build, playback regression checks, and device validation before merging.
6. Measure any claimed playback/startup improvement rather than inferring it from API or version changes.

## Non-goals

- No generic AAR substitution.
- No mixed Media3 versions across Maven modules and local AARs.
- No speculative renderer changes as part of the compatibility repair.
- No build or CI execution without explicit authorization.
