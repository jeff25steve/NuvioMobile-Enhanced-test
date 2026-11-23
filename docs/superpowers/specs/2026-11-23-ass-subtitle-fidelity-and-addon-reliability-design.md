# ASS/SSA Subtitle Fidelity and Add-on Recovery

## Goal
Make authored ASS/SSA subtitles resize correctly without losing their original styling, expose full custom ASS styling only as an explicit opt-in on mpv engines, and ensure late addon subtitles appear and can be auto-selected reliably.

## Evidence
- NuvioMobile PR #2698 documents the ASS sizing regression and its cross-engine fix.
- NuvioMobile PR #2734 documents three reproducible addon subtitle failures: backend reachability incorrectly treated as internet loss, non-observable async state, and early completion of preferred-language selection.
- mpv manual defines `sub-ass-override=no|scale|force`: `no` preserves authored scripts, `scale` adds font scaling, and `force` applies user overrides that can break layout.

## Behavior
1. Existing profiles default to `customizeAss=false`.
2. ASS/SSA at default font size uses `sub-ass-override=no`.
3. A changed font size with customization disabled uses `scale`, sets `sub-scale`, and keeps `sub-pos=100` so authored placement is preserved.
4. Full ASS customization is available only on Android libmpv and iOS mpv and uses `force`; non-ASS tracks never receive ASS overrides.
5. ExoPlayer libass applies the font scale on initial player creation and live style changes.
6. Addon subtitle state changes invalidate Compose independently of playback polling.
7. Only `NetworkCondition.NoInternet` blocks addon fetching; Nuvio backend unavailability does not.
8. Preferred-language subtitle selection remains pending until addon loading completes.
9. Android subtitle font size is 12–60sp; iOS remains 6–40sp. Android libmpv maps 60sp to 183 native units so the engine does not clamp the UI maximum.
10. ExoPlayer libass scale updates are idempotent: unchanged scales do not increment renderer generation or invalidate the overlay again; non-finite scale inputs fall back to 1.0; valid 60sp scales are preserved up to 4.0x.
11. iOS device-loss recovery stores and reapplies both `customizeAss` and `assScale`, and resets per-core applied-property caches before replaying remembered settings.
12. In mpv `sub-ass-override=force`, use the generic `sub-bold` option; do not rely on `sub-ass-style-overrides`, which mpv does not apply in force mode.

## Compatibility and constraints
- No dependency upgrades or new dependencies.
- Existing saved subtitle appearance JSON remains compatible because the new boolean defaults to false.
- Keep iOS five-argument `SubtitleAppearance` construction source-compatible with an explicit secondary constructor.
- No builds, Gradle tests, CI, benchmarks or device playback without authorization.
- Preserve the existing ExoPlayer PiP fractional subtitle sizing already integrated; do not duplicate it.
