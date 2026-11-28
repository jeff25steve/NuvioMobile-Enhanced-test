# Android Bluetooth Direct Volume Control (DVC) feasibility — 28 November 2026

**Target:** main-build-testing  
**Reviewed base:** aee0c8a624b66382d880815a3bfb17ea8e209bd5  
**Decision:** Do not add a generic DVC toggle or a Shizuku-backed Bluetooth volume override.

## User intent

The requested optional setting was inspired by Poweramp Direct Volume Control (DVC), with the intended outcome of cleaner Bluetooth movie audio, better headroom and no extra audio/video delay. The preference would be off by default, native, secure, searchable and compatible with both Nuvio Android playback engines.

The useful product goal is better and reliable Bluetooth movie playback. The Poweramp implementation detail is not itself a portable Android feature.

## What Nuvio already does

The current Android ExoPlayer path is already routed through Media3's native DefaultAudioSink / Android AudioTrack output path:

- PlayerEngine.android.kt builds a DefaultAudioSink with the existing PCM processors. The current processor chain handles the app's PCM gain, downmix, night-mode processing and peak limiting.
- Unless the existing Audio passthrough option is enabled, Nuvio wraps that sink in PcmOnlyAudioSink. This is a PCM-versus-encoded-output decision; it is not Direct Volume Control.
- For the ExoPlayer engine, Nuvio checks the active media output using AudioManager. When it detects Bluetooth, it prefers the FFmpeg audio renderer and requests a stereo 2.0 downmix. This is intended to make surround sources usable on stereo headphones/speakers and retain dialogue level.
- Bluetooth output detection covers A2DP, SCO and Bluetooth LE headset/speaker/broadcast output types. The existing output-key observer debounces device add/remove changes.
- The separate Tunneled playback option is a hardware A/V path preference. It is not DVC and has different subtitle/effect constraints.
- The existing Audio passthrough preference is already present in Playback settings and indexed by the exact search anchor playback-audio-passthrough.

The Bluetooth-specific renderer-priority/downmix policy cited above is in the ExoPlayer construction path. This review does not claim that an identical Bluetooth-specific policy is applied by libmpv.

## Why a generic DVC switch is not safe or truthful

### 1. Android does not expose a universal DVC API

Android's public AudioTrack API accepts PCM data and streams it to the audio sink. Output routing and the selected output thread remain decisions of Android's audio policy, AudioFlinger and the device audio HAL.

Android exposes a direct-playback capability query for a particular AudioFormat and AudioAttributes. A positive capability result is not a general promise that a direct path will be allocated at runtime; the documented API also notes that direct playback may still be re-encoded or mixed when required. It is not an instruction to bypass the system mixer for an arbitrary output.

A direct output path requires the requested format and channel configuration to match a compatible device/HAL route. Bluetooth A2DP output is negotiated by the platform Bluetooth stack and its HAL; an app cannot reliably force a hardware DAC path, Bluetooth codec, sample rate, direct mixer thread or volume-control model on every vendor device.

Consequently, renaming an ordinary AudioTrack.setVolume, a PCM gain processor, Media3 audio offload, tunneling or encoded passthrough to “DVC” would be incorrect. None of those options reproduces Poweramp's device-specific output integration.

### 2. Poweramp's own guidance confirms device and volume-state dependencies

Poweramp documents DVC as conditional on the output method, device and firmware. Its guidance warns that Bluetooth Absolute Volume can conflict with DVC and lead to low/fluctuating volume or distortion. Its FAQ says Android 15+ may prevent reliable Absolute Volume detection, so Poweramp defaults to disabling DVC for Bluetooth when it cannot establish that state.

Poweramp's current Android 17 guidance adds an experimental Shizuku route that turns off Bluetooth Absolute Volume for a particular Bluetooth device. Its current release notes describe a further workaround for DVC-related silence with AAC/SBC on some Android 17 firmware, including Samsung One UI 9.0. That is evidence of a specialised, actively maintained vendor workaround—not a stable, app-independent Android contract.

Recent Poweramp support reports include Bluetooth silence or very low volume when DVC is enabled on Android 16/17 firmware, with codec, Absolute Volume, other system EQ and OEM firmware interactions. These reports are user experiences rather than a universal failure rate, but they are directly relevant to the risk of enabling an unverified path by default or offering an “always works” switch.

### 3. Shizuku is not an acceptable fallback for this feature

Adding Shizuku would require users to grant a separate elevated shell/service capability so Nuvio could change a system Bluetooth volume-sync setting. This changes system-level behavior beyond Nuvio's own audio session, can affect other apps, may be reset by Bluetooth reconnection, and is not available for every Bluetooth mode/device. It still would not provide Nuvio with Poweramp's proprietary output/DSP implementation.

This does not meet the requested native, least-privilege and secure-by-default criteria. Do not add Shizuku, root, hidden API reflection, privileged broadcasts, developer-setting mutation or accessibility automation to imitate DVC.

## Better product decision

1. **Do not add a DVC-labelled switch.** There is no supported, broadly compatible implementation for the current ExoPlayer and libmpv architecture. A toggle that only changes PCM gain or volume would misrepresent its effect.
2. **Keep Android's normal media-volume and Bluetooth Absolute Volume behavior.** Do not silently disable or override the user's system-wide volume synchronization.
3. **Keep the existing Bluetooth stereo downmix path for ExoPlayer.** It directly addresses a movie-specific issue—surround-to-stereo compatibility—without changing Android's global volume model or adding a new processing stage.
4. **Keep Audio passthrough separate.** It is an encoded-audio capability option, not a quality enhancer for generic Bluetooth headphones. Do not automatically enable it when Bluetooth connects or rename it DVC.
5. **Keep tunneling separate.** It is an A/V synchronization/output-path choice and can constrain subtitle and video-effect features; it must not be used as a DVC substitute.
6. **Do not add a dependency or change Media3.** The repository is aligned to Media3 1.8.0 and a custom AAR family. The DVC investigation provides no evidence that a Media3 version bump or a new library would unlock a supported generic DVC API.

If Android later exposes a public, documented output-control API that can positively report the active route and its volume-control/direct-output capabilities, evaluate it as a capability-aware Android-only output preference. It must fall back to the standard route without changing global settings, preserve both engines' A/V sync, and prove compatibility on real Bluetooth devices before any user-facing toggle is approved.

## Settings and UX guidance

Apple's Human Interface Guidelines recommend defaults that work for the largest group, minimising the number of settings, avoiding redundant copies of system-wide controls, and keeping task-specific options in context. AOSP settings guidance asks for brief, meaningful, non-jargon titles, visible state, intuitive grouping and easy discovery.

Applying that guidance here:
- Do not expose an inaccurate “Direct Volume Control” label or a dormant/off-by-default switch that cannot perform the advertised action.
- Keep the real Audio passthrough option searchable under its existing name and search anchor.
- If a future supported option is designed, describe its actual effect in plain language, keep it Android-only, and make the current value/status clear. Avoid “better sound”, “lossless”, “no resampling”, “more headroom” or “zero delay” claims unless the active route can prove them.

## Validation boundary

This is a source-and-documentation decision, not a playback implementation. No Kotlin source, settings values, dependencies, audio routing, player engine behavior or tests were changed. No Gradle task, test, compilation, build, CI, benchmark or device playback was run.

A future capability-aware output feature would require device tests across ExoPlayer and libmpv; A2DP and LE Audio; SBC, AAC and LDAC-capable equipment; Android/OEM firmware versions; Absolute Volume on/off states; HDMI/USB/wired routes; playback pause/resume; audio focus changes; and measured A/V sync before release.

## Sources

- [Android AudioTrack API](https://developer.android.com/reference/android/media/AudioTrack)
- [Android AudioManager direct playback support](https://developer.android.com/reference/android/media/AudioManager#getDirectPlaybackSupport(android.media.AudioFormat,android.media.AudioAttributes))
- [The Android audio stack from a music player's perspective (9 August 2025)](https://nift4.org/2025/08/09/android-audio-stack-music-player/)
- [Poweramp: Using Direct Volume Control (DVC)](https://forum.powerampapp.com/kb/en_us/guides/using-direct-volume-control-dvc-r42/)
- [Poweramp: Volume issues / Android 15+ Absolute Volume behavior](https://forum.powerampapp.com/kb/en_us/faq/volume-issues-r7/)
- [Poweramp current feature notes: DVC via Shizuku on Android 17+](https://powerampapp.com/)
- [Poweramp build 1036 release notes: Android 17 Bluetooth DVC AAC/SBC silence workaround](https://forum.powerampapp.com/files/file/107-poweramp-build-1036-uniapk/)
- [Poweramp community report: One UI 9 / Android 17 Bluetooth DVC output failure](https://forum.powerampapp.com/topic/31793-no-more-soundvery-low-volume-samsung-oneui-90-btdvc-android-17-update/)
- [Poweramp community report: HyperOS 4 / Android 17 Bluetooth DVC routing failure](https://forum.powerampapp.com/topic/31807-aggressive-dvc-bluetooth-routing-failure-update-regression/)
- [Apple Human Interface Guidelines — Settings](https://developer.apple.com/design/human-interface-guidelines/settings)
- [AOSP Settings design guidelines](https://source.android.com/docs/core/settings/settings-guidelines)
