# In-App One-Handed Mode — Design Specification

**Date:** 2026-10-21  
**Repository:** `jeff25steve/NuvioMobile-Enhanced-test`  
**Implementation branch:** `one-hand-operation` (created from `enhanced`)  
**Status:** Revised design incorporates the user's latest requirements; pending spec review

## 1. Goal

Add an optional, Samsung One-handed Mode-inspired presentation for Nuvio's ordinary app UI so the interface can be used more comfortably with one hand. This is an in-app layout feature only. It must not run a background service, draw system-wide overlays, or control Android system UI or other apps.

## 2. Confirmed user requirements

- The normal tap on the Library navigation item continues to navigate to Library.
- Long-pressing Library toggles one-handed mode.
- The existing profile-button press-and-hold interaction is the reference implementation: inspect and reuse its actual gesture timing, pointer handling, and interaction conventions rather than inventing a second long-press detector.
- Long-pressing Library uses that same proven press-and-hold recognition, but its action is to toggle one-handed mode directly—not to show the profile-selection popup or any new popup. A normal tap on Library still navigates to Library.
- Keep the profile button's current press-and-hold profile-switching popup and behaviour unchanged.
- The floating navigation bar has a custom drag-to-select gesture detector (`detectJellyTabGestures`). Integrate the Library hold action into the existing gesture flow (or a demonstrably non-competing extension of it); do not stack a competing pointer-input detector or break drag-to-select. If the current profile hold uses a separate proven mechanism, adapt its timing/semantics while preserving the nav bar's drag and tap paths.
- The app's ordinary UI is proportionally reduced and aligned near the bottom, leaving an outside region above it that does not dismiss one-handed mode when tapped.
- Outside taps must not activate hidden underlying UI; exit is explicit via the close control or Library long-press.
- The reduced surface has a user-adjustable size with a visible drag handle, continuous resizing feedback, and sensible minimum/maximum bounds.
- Android and iOS use the same user-facing layout, visual styling, gestures, resize rules, controls, and state transitions wherever platform capabilities permit.
- The reduced interface remains fully interactive: taps, scrolling, navigation, dialogs, popups, and Liquid Glass effects must behave correctly.
- Provide left/right positioning if it can be implemented without compromising existing layout or gestures.
- Tapping outside the reduced app content leaves one-handed mode active and must not trigger underlying controls.
- **Opening any video or trailer disables one-handed mode immediately.** Playback uses the normal layout. Exiting playback must not re-enable one-handed mode automatically.
- Playback state, progress, and player controls must not be reset or disrupted by the transition.
- All feature processing stays on-device. Do not upload screenshots, UI pixels, layout state, or feature telemetry to any server; do not add a network dependency for this feature.
- No background service, foreground service, persistent notification, overlay permission, system-wide manipulation, or background monitoring.
- Keep the implementation scoped to the current app UI and avoid unrelated refactors.

## 3. Outside-region backdrop design

**Chosen direction: prefer a live full-screen backdrop behind the reduced interactive UI, with a one-time snapshot fallback and an adaptive frosted-glass fallback. Android and iOS should share the same visible treatment.**

When one-handed mode is enabled:
1. First evaluate whether the existing UI can remain live at normal size behind the reduced interactive surface without duplicating expensive composition or changing navigation/player ownership. The live backdrop must update naturally as the visible page changes.
2. Apply the same restrained blur and dimming treatment on Android and iOS. Keep the backdrop non-interactive so touches outside the reduced surface are consumed without activating hidden content.
3. If a live backdrop would require a second active composition, disrupt state, cause unacceptable GPU/memory/battery cost, or conflict with protected video surfaces, prefer a one-time in-memory capture of the normal-size page before scaling.
4. If safe capture is unavailable or fails, use a static/adaptive frosted-glass material and subtle theme-aware gradient as a consistent fallback.
5. Do not continuously recapture or blur in a background loop. Release snapshots promptly on exit, player entry, disposal, or replacement.

The snapshot must not be continuously refreshed or blurred in a background loop. Do not add an accessibility service, overlay permission, background service, or persistent worker to support it. Avoid retaining large bitmap resources longer than necessary. Prefer platform-supported, lifecycle-aware capture and a modest blur implementation; assess memory cost and rendering performance before choosing the concrete mechanism. Never capture protected player surfaces or compromise secure content.

## 4. Recommended app architecture

Use a single app-level presentation boundary to apply the one-handed transform consistently to ordinary app content. Keep mode state in a small UI-scoped controller/state holder owned by the app composition, rather than distributing independent flags across screens.

The presentation boundary must coordinate:
1. normal content layout and proportional scale;
2. bottom alignment and left/right alignment;
3. touch-coordinate mapping and non-dismissing outside-region input handling;
4. live backdrop, snapshot fallback, blur, dimming and resource lifecycle;
5. continuous user-driven resize state and bounds;
4. existing navigation gestures and scroll interactions;
5. dialogs, menus, popups, and Liquid Glass rendering;
6. player-route detection and synchronous mode disablement before entering video/trailer playback.

Verify actual Compose/KMP ownership boundaries in `MainAppContent.kt`, `RootTabHost.kt`, `FloatingNavigationBar.kt`, and the player route/host code before deciding where the transform and snapshot belong. Do not assume every overlay or player surface is a descendant of one transform.

### Why this approach

An app-level boundary is more consistent than scaling each screen separately and less invasive than rebuilding the app inside a second miniature UI tree. A single boundary also centralizes hit testing, mode lifecycle, and backdrop disposal. The transform and snapshot should exist only while normal app content is displayed; neither should create work that continues in the background.

## 5. State and transitions

- **Disabled → Enabled:** long-press Library while on ordinary app UI.
- **Enabled → Disabled:** long-press Library again, use the explicit exit control, or initiate opening any video/trailer.
- **Playback:** disable one-handed mode and release the snapshot before the player becomes visible; player uses the normal, unscaled layout.
- **After playback:** remain disabled. Do not restore the prior enabled state.
- **Navigation between ordinary screens:** retain the enabled state unless a disable trigger occurs. If the page changes while mode remains enabled, ensure the backdrop cannot display stale content; refresh it only at a well-defined foreground transition if needed, or use the fallback.
- **App backgrounding/recreation:** no background worker or service is allowed. Persisting the enabled preference across process recreation is not required for the first version; default safely to disabled after a fresh app composition/process if persistence cannot be added without broader architectural change.

If route state can briefly report a player after its UI starts, explicitly disable mode from the video/trailer navigation action as well as using player-route detection as a defensive guard. Avoid asynchronous timing that allows the player to appear scaled or captured for a frame.

## 6. Interaction and visual requirements

- Preserve the existing Library tap and drag-to-select navigation behaviour.
- Match the existing profile press-and-hold interaction's timing and pointer semantics, but route the Library hold directly to one-handed-mode toggle (no profile popup or other popup). Preserve normal Library taps and integrate with `detectJellyTabGestures` so its drag-to-select remains intact; avoid competing pointer-input detectors.
- Taps on content within the reduced region must reach the correct controls; do not rely on a visual-only scale.
- Taps in the outside region do not dismiss the mode and never activate underlying UI.
- Existing dialogs and popup menus should remain legible and anchored to the correct controls. If a platform dialog or popup cannot safely participate in the transform, document and handle it deliberately rather than allowing misaligned coordinates.
- Preserve current Liquid Glass appearance, shape clipping, backdrop/effect bounds, and hit areas. No new background rendering loop or continuous polling.
- Left/right positioning must not mirror content or change navigation order; it changes placement only.
- Outside backdrop blur/dimming should be subtle and theme-aware. Prefer a live page when it can be rendered safely and efficiently; otherwise use an ephemeral snapshot, then a consistent frosted-glass fallback. The background must not receive user interaction.
- Provide a visible resize handle. Dragging it changes scale continuously within explicit min/max bounds; it must not trigger page scrolling or side switching. Resizing must not recapture the backdrop.
- On capture failure, invalid dimensions, or resource pressure, fall back to the frosted-glass treatment and retain a fully usable normal or reduced UI rather than crashing.

## 7. Video and trailer compatibility

The video/trailer entry path is a hard boundary. Before playback is shown, one-handed mode is turned off, the snapshot is released, and the mode state is cleared. The player must use its existing normal layout, surface ownership, controls, orientation/insets handling, and playback lifecycle. Leaving playback must not re-enable the feature.

The feature must not alter media selection, playback state, resume position, watched state, or mini-player ownership. If the app can host a mini-player while ordinary browsing continues, verify the intended player route/host behaviour explicitly and ensure one-handed mode does not keep the player surface scaled, captured, or touch-transformed.

## 8. Non-goals

- Android system-wide One-handed Mode or changing device settings.
- Operating over other apps, the home screen, notification shade, or system UI.
- Accessibility-service or overlay-based implementation.
- Background/foreground services, persistent notification, polling, continuous snapshotting, or background monitoring.
- Automatically restoring one-handed mode after video/trailer playback.
- Redesigning the navigation bar or replacing existing Liquid Glass code.
- Unrelated player, navigation, or layout refactors.

## 9. Error handling, privacy, and performance

- Fail closed to the normal layout if the mode controller or transform cannot establish valid dimensions.
- Ensure orientation, window-size changes, and insets do not leave content clipped or unresponsive.
- Do not allow outside-region taps to pass through and activate hidden content.
- Avoid duplicate long-press handlers or gesture detectors that compete with the existing navigation bar.
- Keep all capture, blur, transform, gesture handling, and state transitions on-device. Treat captured pixels as ephemeral in-memory UI resources: no disk writes, network transmission, analytics uploads, logs, or persistent storage. Release promptly when no longer needed.
- Avoid capture/blur for the player route and any secure/protected content.
- Do not change product source code until the feature request has explicit repository-maintainer approval, as required by `CONTRIBUTING.md`.

## 10. Verification plan

### Behaviour checks
- Long-press Library enables/disables mode; normal tap still navigates.
- Profile long-press still switches profiles and does not toggle one-handed mode.
- Drag-to-select navigation remains functional.
- Left/right placement works without mirroring or reordering content.
- Outside-region tap dismisses mode and does not trigger underlying controls.
- Scrolling, buttons, navigation, dialogs, popup menus, and Liquid Glass controls work at transformed coordinates.
- Live backdrop tracks visible page changes without receiving touch; when live rendering is unsafe, the snapshot visually matches the normal-size page at activation, is blurred/dimmed, and does not intercept touch.
- Resize handle changes size continuously within bounds and preserves correct touch mapping.
- Outside taps do not exit one-handed mode or activate underlying controls.
- Android and iOS match the same visual and interaction specification, with documented platform limitations only where required.
- Snapshot is disposed on exit, page/lifecycle teardown, and video/trailer launch; fallback works when capture fails.
- Opening each supported video/trailer route disables mode and releases backdrop before player presentation.
- Player remains unscaled and uncaptured; controls and playback continue normally.
- Exiting playback leaves one-handed mode disabled.
- Navigation among ordinary tabs retains mode where appropriate without showing stale backdrop content.
- Rotation/window-size changes and insets do not break layout or input.
- No background service, notification, overlay permission, polling loop, persistent screenshot, or unnecessary background activity is introduced.
- Memory and rendering cost are checked on representative devices; if capture/blur is too expensive, use fallback.

### Quality checks
- Add focused tests for state transitions and route-triggered disablement where the existing test architecture permits.
- Run relevant unit/common tests and platform build(s) available to the project.
- Manually verify navigation gestures, transformed hit testing, overlays, Liquid Glass, backdrop disposal, and player transition on a real Android device/emulator.
- Capture before/after evidence and record exact checks performed. Do not claim tests/builds passed unless actually run.

## 11. Implementation scope candidates

Likely inspection/implementation areas:
- `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/RootTabHost.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/FloatingNavigationBar.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/NavigationBar.kt`
- the existing player route/host and the navigation actions that open videos and trailers

These are investigation targets, not a commitment to modify every file. Final file scope must follow code inspection and remain minimal.
