# Android libmpv independent scaling filters — design

Date: 23 November 2026  
Target: `main-build-testing`

## User outcome

Give users meaningful control over picture reconstruction on different screens and source resolutions. Expose independent upscaling, downscaling and chroma filters for Android libmpv, and apply changes during playback without restarting the stream.

## Current implementation

- Nuvio already has Balanced, High Quality and Performance mpv profiles, plus independent debanding and Anime4K controls.
- The High Quality profile maps to mpv's upstream `high-quality` profile; current upstream mpv 0.42.0 defines it as `scale=ewa_lanczossharp`, anti-ringing 0.6 and stronger HDR peak/contrast analysis.
- The Performance profile maps to `fast`, which sets `scale=bilinear`, `dscale=bilinear`, and disables dithering / advanced scaling corrections.
- Nuvio does not currently expose independent `scale`, `dscale` or `cscale` choices.
- The current Android libmpv dependency is `io.github.abdallahmehiz:mpv-android-lib:0.2.1`; its native binary is built from mpv source. The options being used are longstanding mpv GPU renderer options and are not tied to a Media3 API.

## Design

Add one shared filter enum and three Android libmpv settings:
- Upscaling filter → mpv `scale`
- Downscaling filter → mpv `dscale`
- Chroma filter → mpv `cscale`

Each setting has a `Default` value that follows the selected Nuvio render-quality profile. This is important: default must preserve current profile behavior, not silently override it.

Filter choices:
- Default (profile-managed)
- Bilinear
- Mitchell
- Spline36
- Lanczos
- EWA Lanczos
- EWA Lanczos Sharp
- Hermite
- Catmull-Rom

Profile-managed values:
- Balanced: upscaler Lanczos, downscaler Hermite, chroma follows upscaler.
- High Quality: upscaler EWA Lanczos Sharp, downscaler Hermite, chroma follows upscaler.
- Performance: upscaler Bilinear, downscaler Bilinear, chroma follows upscaler.

When a user explicitly chooses a filter, that choice overrides the profile for that axis only. When changing render-quality profile, reapply the independent filter selections after the profile so the user's custom values remain stable. When returning an axis to Default, resolve it against the currently selected profile.

Apply options during libmpv initialization after the profile, and update them live through the documented writable `options/<name>` properties. Do not alter decoder, video output driver, frame-rate matching, interpolation, buffering, HDR passthrough or the default profile.

## UX

- Add three settings rows under the existing Android libmpv decoder settings: Upscaling Filter, Downscaling Filter, Chroma Scaling Filter.
- Each opens the existing choice dialog.
- Show the selected filter label as the row description.
- Explain that EWA filters can cost more GPU time and may show ringing on some devices; users can fall back to Lanczos or Spline36.
- Default values remain profile-managed and therefore preserve all current installations' appearance until users change them.

## Evidence

- mpv stable manual: `scale`, `dscale` and `cscale` are GPU renderer options supported by gpu, libmpv and gpu-next. `cscale` follows the upscaler when unset: https://mpv.io/manual/stable/#gpu-renderer-options
- mpv 0.42.0 built-in profiles: `etc/builtin.conf` defines exact fast and high-quality settings: https://github.com/mpv-player/mpv/blob/master/etc/builtin.conf
- Official mpv-android `MPVView.kt` passes separate upscaling and downscaling preferences into mpv: https://github.com/mpv-android/mpv-android/blob/master/app/src/main/java/is/xyz/mpv/MPVView.kt
- Official mpv-android README lists advanced scalers and debanding as core rendering settings: https://github.com/mpv-android/mpv-android
- Community issue #13886 documents a legacy `gpu` polar-scaler artifact on certain Android GLES drivers while `gpu-next` did not reproduce it in that report: https://github.com/mpv-player/mpv/issues/13886

## Safety and compatibility

- No automatic switch to a sharper filter; all new settings default to Default/profile-managed.
- Do not force EWA filters globally.
- Do not modify ExoPlayer or iOS video rendering.
- Do not add dependencies or upgrade the mpv AAR in this change.
- Keep settings profile-scoped and included in settings sync, matching existing Android libmpv preferences.
- No Gradle tests, compilation, builds, CI, benchmarks or device playback without explicit authorization.

## Acceptance

- Three filters persist across app restart and settings sync.
- Default resolves to current profile's documented filter values.
- Changing render quality preserves explicit user filter choices.
- Changes are sent live to mpv without reloading media.
- Unknown stored enum values safely fall back to Default.
- Tests cover profile resolution, each independent override and switching profiles.
