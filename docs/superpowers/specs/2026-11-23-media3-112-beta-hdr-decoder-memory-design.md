> **Superseded on 24 November 2026.** This proposal to adopt Media3 1.12.0-beta01 was not retained. The live project uses the verified Media3 1.8.0 custom AAR set (see `gradle/libs.versions.toml` and `scripts/tests/test-media3-custom-aar-compatibility.sh`). Do not implement this design or upgrade Maven artifacts independently. A future Media3 upgrade requires rebuilding and fingerprinting the complete custom AAR family, then validating API 24 compatibility and playback before adoption.

# Media3 1.12.0-beta01 HDR, decoder and memory improvements — design

## Intent

Use the current Media3 1.12.0-beta01 release on the `main-build-testing` branch to pick up concrete upstream improvements to HDR stream identification, Dolby Vision compatibility and decoder selection, and sample-buffer memory use. This is a testing-branch adoption, not a production-release declaration.

## Goals

- Parse HLS `VIDEO-RANGE=PQ` and `VIDEO-RANGE=HLG` metadata into `Format.colorInfo`, so the existing Media3 track-selection pipeline can make display-capability-aware decisions with accurate variant metadata.
- Improve compatibility with backward-compatible Dolby Vision Profile 7 streams through HEVC fallback.
- Avoid selecting a primary decoder that reports the wrong Dolby Vision profile when no fully capable decoder exists.
- Reduce Media3 sample-buffer allocation size from 65,536 to 65,520 bytes; upstream reports approximately 6% lower sample-buffer memory on 4 KiB page devices and 20% on 16 KiB page devices.
- Keep every Media3 artifact aligned through the existing version catalog.
- Keep the project minimum SDK at 24, which matches Media3 1.12's minimum.

## Non-goals

- No speculative decoder-priority, bitrate, buffer-duration, frame-rate, tunneling or surface changes.
- No custom HLS parser or duplicate HDR metadata implementation.
- No changes to iOS/AVPlayer, libmpv, custom software-renderer ordering, subtitle rendering, audio processing or user settings.
- No enabling renderer prewarming: this player overrides video-renderer creation and does not supply a matching secondary custom renderer.
- No claim of measured playback, HDR or memory improvement until builds and device validation are authorized and completed.

## Approaches

1. **Remain on stable Media3 1.11.1.** Lowest dependency risk, but misses the upstream HLS HDR variant metadata parsing, Dolby Vision Profile 7 fallback/profile selection fixes, and buffer segment reduction.
2. **Move all catalog-managed Media3 artifacts together to 1.12.0-beta01 (selected).** The project is already minSdk 24 and its custom renderer overrides use extension points retained in the beta. This obtains the concrete upstream improvements while confining the pre-release adoption to the testing branch. Requires compilation and playback validation before any production release.
3. **Reimplement individual fixes locally.** Rejected: HLS playlist parsing, Dolby Vision decoder capability selection and buffer allocation are Media3 internals; duplicating them in Nuvio would be brittle and less correct than consuming the upstream release.

## Compatibility decisions

- The beta enables loading only selected video/image tracks by default for progressive media. Keep this upstream behavior for the testing branch and explicitly include progressive multi-video-track switching in the later device-validation matrix. A newly selected progressive video track may need additional loading/buffering; do not claim seamless switching without testing.
- HLS adaptive selection remains Media3-managed; no custom quality resolver changes are included.
- Keep HAGC metadata parsing enabled. Media3 1.11.1 already defaults `DefaultExtractorsFactory.setParseHagcMetadata` to true; do not add a redundant override.
- Preserve the existing custom `DefaultRenderersFactory` implementation and do not enable MediaCodec renderer prewarming.
- Keep the beta out of a production release until the required build, regression tests and real-device playback matrix pass.

## Verification boundary

- Static compatibility checks can verify catalog alignment, minSdk and absence of known removed API references.
- No Gradle tasks, tests, compilation, builds, CI, APK/AAB, benchmarks or device playback are run without explicit user authorization.
- Device validation must cover HLS SDR/PQ/HLG variants; Dolby Vision Profile 7 with and without native DV decoder support; unsupported DV profiles; progressive files with multiple video tracks and switching; HLS quality changes; subtitles; software-decoder fallback; and API 24+ startup/playback.
