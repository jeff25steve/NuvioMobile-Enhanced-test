# Liquid Glass backdrop regression — 10 November 2026

## Source state and boundaries

- Target base: `main-build-testing` at `103b3348431a5a5c43794e6d6f1975fb32c60f06`.
- Original Liquid Glass integration source: `pr/liquid-glass-editor-final-20261101` at `c523478227818278c2b382e405ffd23f673667bd`. PR #5 was closed without merging; its source branch is the reference implementation, not a replacement for the current branch.
- The current branch is 518 commits ahead of the original source branch. Do not restore the 195-file PR snapshot wholesale: later search, Library, native iOS, playback and other mobile work must be retained.
- No local checkout is mounted in this environment; local `git status` and uncommitted local changes cannot be inspected. Changes are isolated on a new branch created from the exact remote head.

## Confirmed regressions and changes in this batch

### Search backdrop and header composition

- The PR source had a full-screen backdrop source around the scrolling Search catalogue, with `NuvioScreen(topPadding = searchHeaderHeight)` beneath a foreground header.
- Current `SearchScreen` had moved the header into a preceding opaque `Column` child, put the list in a weighted viewport below it, and retained a second foreground header overlay. The screen no longer attached `nuvioBackdropSource(searchBackdrop)` to the full-screen scrolling surface. The glass field consequently had no live catalogue pixels to sample; the first header also remained underneath the overlay.
- Restored the full-viewport `NuvioScreen` underlay and foreground-header layering. The measured header height is now the list's top content inset, and the list background is transparent while Search glass is enabled, allowing poster/content pixels to move beneath the glass while preserving the current Discover title, reveal-search animation, search callbacks and list state.
- Backdrop capture is enabled only for the Compose renderer. Native iOS search controls own their native material and do not need an additional Compose graphics-layer capture; unsupported targets use the existing static glass fallback.

### Shared renderer readiness

- `NuvioBackdrop.isReady` becomes true only after `onGloballyPositioned` supplies attached source coordinates. Before that, `NuvioBackdrop.drawBackdrop` intentionally returns without drawing.
- The shared `liquidGlassSurface` previously selected the live renderer whenever the backdrop object was non-null. A newly allocated but unpositioned host was therefore passed to the renderer as if it had usable pixels.
- Added a common readiness predicate and routed the renderer through the existing `isLiquidGlassBackdropReady` check. It now shows the established adaptive static film until the source is actually ready; the state read also participates in Compose snapshot observation so the caller can recompose when the source becomes ready.
- Added common unit coverage for missing, unpositioned, ready and unsupported backdrop cases.

## Cross-surface audit status

- **Library:** screen and scrolling-content capture layers are still present, including the separate content backdrop used by Saved pinned controls and the release calendar. Kept these newer layers and their layout untouched.
- **Catalog/category header:** the catalog screen still attaches its source and supplies it to the title/back-button header.
- **Settings:** the main Settings surface remains disabled by default by design; Profile Watch Time and Supporter Membership have their own enabled surfaces and the settings-page wrapper requests capture for those specific routes. Do not enable the whole Settings surface to solve unrelated glass failures.
- **Player:** the shared Compose renderer remains in use. iOS live-video native glass is explicitly not considered complete: the existing design plan requires a real iOS 26+ MPV/Metal sampling proof before introducing UIKit video overlays. Do not fake that capability or alter the video rendering path without the required proof.
- **Bottom sheets:** Android continues to use the Compose sheet glass path. iOS uses the native page sheet and its existing OS-version/per-surface gate. No blanket sheet routing change was justified.
- **Passive content cards:** cloud cards and detail cards have deliberately conservative/off-by-default recipes; preserve those user choices.

## Research and evidence

- GitHub source/commit history: PR #5, original branch `pr/liquid-glass-editor-final-20261101`; Search changes in commits `6c88d7b3d14990d45ad78d5f627290ae4e247ec1` and `065a0f5782663f5d87c117b1b1c364864c8d9935`; current branch file history for `SearchScreen.kt`, `LibraryScreen.kt` and `LiquidGlass.kt`.
- Local implementation evidence: `NuvioBackdropSourceNode` records source content to `GraphicsLayer`; `NuvioBackdrop.drawBackdrop` requires attached source/target coordinates; renderer gate is in `LiquidGlass.kt`.
- Context7 identified the Kyant Backdrop documentation and JetBrains Compose Multiplatform references. These were used as API context only; the patch is grounded in this repository's exact dependency call sites and implementation.
- No external claim about iOS MPV/Metal compatibility was used to justify changing player rendering.

## Verification boundary

- Source-level diff and call-site structure were reviewed before writing.
- No Gradle build, test task, CI workflow, simulator or device run was started, in accordance with the explicit restriction.
- Kotlin compilation, Compose layout/runtime behavior, Android live blur and iOS visual parity remain unverified until an authorised build/device pass.

## Next starting point

1. Review Search on Android with fast scrolling: posters should pass beneath the pinned glass field without a duplicate opaque header or jump when the measured header height changes.
2. Review Search on iOS 26+: native search input should remain native; no unnecessary Compose backdrop capture should be active.
3. Sweep every `LiquidGlassSurface` enum member against the actual runtime consumer and the backdrop source it receives. Keep intentional disabled defaults distinct from missing runtime application.
4. Triage reported visual glitches with the affected screen/control and screenshots; do not copy older PR files over later mobile features.
