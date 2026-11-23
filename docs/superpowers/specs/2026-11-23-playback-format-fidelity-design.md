# Playback format fidelity and diagnostics — design

Date: 23 November 2026  
Target: `main-build-testing`

## Intent

Make the integrated player's playback information trustworthy and more useful, and harden the HLS quality metadata path added earlier today. The user-visible outcome is clearer source format information: declared HLS range/frame rate, actual decoded bit depth and color range, and correct Dolby Vision / PQ / HLG / SDR classification. The parser must also preserve quoted HLS URIs containing dollar-sign tokens.

## Findings from the live code audit

1. `Format.hdrFormatForPlaybackInfo()` recognizes Dolby Vision MIME, `dvhe` and `dvh1`, but omits the `dvav`, `dva1` and `dav1` codec families already recognized by Nuvio's `Format.hdrFormats()` / `exoVideoCodec()`. This can mislabel a Dolby Vision track that is exposed through a fallback MIME type.
2. The ExoPlayer playback-info JSON deliberately writes an empty `pixelFormat` and omits bit depth and color range, even though `Format.exoBitDepth()` already extracts bit depth and `ColorInfo` carries color-range data.
3. The libmpv playback-info JSON already reads pixel format and color levels but does not provide normalized bit depth/color-range rows to the shared UI.
4. libmpv currently treats BT.2020 primaries alone as HDR. Primaries identify gamut, not transfer function; wide-gamut SDR must not be labelled HDR. mpv's `video-params/colorlevels` and `video-params/colormatrix` are available and documented; the latter explicitly identifies Dolby Vision as `dolbyvision`.
5. The HLS metadata tests cover display helpers but do not test parsing of `VIDEO-RANGE` / `FRAME-RATE` attributes or the quoted-URI replacement helper. Its replacement-string interpolation also treats dollar signs as regex group references.

## Chosen approach

### 1. Normalize and test HLS metadata and quoted URI rewriting

- Extract a small common helper that reads the uppercase `VIDEO-RANGE` and `FRAME-RATE` keys produced by the existing HLS attribute parser.
- Retain only `PQ`, `HLG`, and `SDR` range values; ignore unknown values.
- Keep frame-rate parsing finite and positive.
- Make URI-attribute rewriting use a replacement lambda so dollar signs in resolved URLs are literal.
- Add common tests for case/whitespace normalization, unknown ranges, malformed/non-finite rates, and quoted URI replacement containing `$1` / `$token`.

### 2. Correct dynamic-range classification for both engines

- ExoPlayer: recognize Dolby Vision MIME and all existing codec families (`dvhe`, `dvh1`, `dvav`, `dva1`, `dav1`) consistently in playback-info classification and profile extraction.
- libmpv: classify Dolby Vision from selected track profile/codec/decoder or `video-params/colormatrix=dolbyvision`; classify PQ and HLG from their transfer functions; retain SDR only when mpv explicitly reports SDR.
- Do not infer HDR from BT.2020 primaries alone. PQ is shown as `HDR (PQ)`, not automatically `HDR10`, unless the engine can prove the more specific format.
- Add pure helper tests for codec-family and transfer-function combinations, including consistent PQ/Dolby Vision badges in the track-info model.

### 3. Surface actual bit depth and color range in Playback Info

- ExoPlayer JSON: use the existing `Format.exoBitDepth()` helper and `ColorInfo.colorRange` to expose numeric bit depth and normalized `Full` / `Limited` range.
- libmpv JSON: use the decoded `video-params/pixelformat` (not only post-filter `video-out-params`) for bit depth and normalize mpv's `colorlevels` values.
- Shared Playback Info UI: show bit depth and color range only when known; show declared HLS dynamic range and frame rate in the Selected Quality section.
- Omit unknown dynamic range instead of displaying a misleading `Unknown` row.

## Approaches considered

1. **Improve the existing format/JSON/UI path (chosen).** Uses actual selected track metadata already available in each engine, adds no dependencies, and keeps the shared Playback Info presentation consistent.
2. **Port Media3 PR #3461 or implement custom HDR-aware adaptive selection.** Rejected for this cycle. PR #3461 is still open and changes DefaultTrackSelector ranking, adaptive compatibility and display-change reselection. Nuvio's custom renderers and quality resolver make a partial local imitation risky; this requires a separately designed and tested integration once upstream ships a stable artifact.
3. **Only add labels in the HLS quality list.** Rejected as insufficient: it would not correct current-format diagnostics or expose actual decoded bit depth/color range.

## Goals

- Make dynamic-range labels truthful for ExoPlayer and libmpv.
- Expose source bit depth and color range in Playback Info when known.
- Show HLS-declared dynamic range and frame rate in Selected Quality details.
- Protect HLS attribute parsing and URI rewriting with common regression tests.
- Preserve Auto playback, manual quality selection, decoder ordering, frame-rate switching, subtitle handling and playback source URLs.

## Non-goals

- No custom HDR track-selection policy or display capability override.
- No decoder, buffer, surface, frame-release or frame-rate policy tuning.
- No changes to iOS/AVPlayer or native MPVKit.
- No claim that format metadata proves the display actually rendered HDR correctly.

## Constraints

- Keep Media3 at the already-selected `1.12.0-beta01` testing-branch version.
- Do not add dependencies.
- No Gradle tests, compilation, builds, CI, benchmarks, APK/AAB or device playback without explicit authorization.
- Update the existing engineering notes without duplicating the research record.

## Evidence

- Media3 PR #3461 remains open; it ranks HDR according to the default display's supported HDR types, separates incompatible adaptive video ranges and invalidates track selections when display HDR types change: https://github.com/androidx/media/pull/3461
- Android HDR playback requires both a capable decoder and an HDR-capable display; a PQ/HLG transfer signal is not proof of successful HDR output: https://developer.android.com/media/grow/hdr-playback
- mpv documents `video-params/pixelformat`, `video-params/colorlevels`, `video-params/colormatrix` and `video-params/gamma`; its discussion explains Dolby Vision and HDR10+ signal detection: https://mpv.io/manual/stable/#video-params and https://github.com/mpv-player/mpv/discussions/14024
- Maintained Just Player demonstrates practical support for HDR10+/Dolby Vision and Dolby Vision Profile 7 as HDR HEVC, but this design does not copy an unverified private patch: https://github.com/moneytoo/Player
- Community reports of decoder teardown failures on in-player episode transitions were reviewed; Nuvio's ExoPlayer instance is keyed by source URL, so source changes rebuild the player rather than reusing the same instance in that scenario: https://github.com/jellyfin/jellyfin-androidtv/issues/5908

## Acceptance

- HLS metadata normalization and quoted-URI replacement have deterministic common tests.
- ExoPlayer dynamic-range helpers correctly identify all supported Dolby Vision codec families and preserve PQ/HLG/SDR classification.
- libmpv no longer labels BT.2020-only SDR as HDR, and Dolby Vision matrix/profile/codec signals are handled.
- Both engines expose bit depth and normalized color range in the shared Playback Info modal when available.
- Existing playback selection and rendering policies remain unchanged.
- Test sources are added but remain explicitly unexecuted until the user authorizes Gradle execution.
