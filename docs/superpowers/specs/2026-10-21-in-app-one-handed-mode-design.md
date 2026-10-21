# In-App One-Handed Mode — Design Specification

**Date:** 2026-10-21  
**Repository:** `jeff25steve/NuvioMobile-Enhanced-test`  
**Target branch:** `feature/navigation-liquid-glass`  
**Status:** Draft for user review; implementation not approved by repository maintainer yet

## 1. Goal

Add an optional, Samsung One-handed Mode-inspired presentation for Nuvio's ordinary app UI so the interface can be used more comfortably with one hand. This is an in-app layout feature only. It must not run a background service, draw system-wide overlays, or control Android system UI or other apps.

## 2. Confirmed user requirements

- The normal tap on the Library navigation item continues to navigate to Library.
- Long-pressing Library toggles one-handed mode.
- The existing profile-button long-press for profile switching must remain independent and unchanged.
- The app's ordinary UI is proportionally reduced and aligned near the bottom, leaving a tappable empty region above it.
- The reduced interface remains fully interactive: taps, scrolling, navigation, dialogs, popups, and Liquid Glass effects must behave correctly.
- Provide left/right positioning if it can be implemented without compromising existing layout or gestures.
- Tapping outside the reduced app content exits one-handed mode.
- **Opening any video or trailer disables one-handed mode immediately.** Playback uses the normal layout. Exiting playback must not re-enable one-handed mode automatically.
- Playback state, progress, and player controls must not be reset or disrupted by the transition.
- No background service, foreground service, persistent notification, overlay permission, system-wide manipulation, or background monitoring.
- Keep the implementation scoped to the current app UI and avoid unrelated refactors.

## 3. Recommended architecture

Use a single app-level presentation boundary to apply the one-handed transform consistently to ordinary app content. Keep mode state in a small UI-scoped controller/state holder owned by the app composition, rather than distributing independent flags across screens.

The presentation boundary must coordinate:
1. normal content layout and proportional scale;
2. bottom alignment and optional left/right alignment;
3. touch-coordinate mapping and outside-region dismissal;
4. existing navigation gestures and scroll interactions;
5. dialogs, menus, popups, and Liquid Glass rendering;
6. player-route detection and synchronous mode disablement before entering video/trailer playback.

The implementation must first verify the actual Compose/KMP ownership boundaries in `MainAppContent.kt`, `RootTabHost.kt`, `FloatingNavigationBar.kt`, and the player route/host code. Do not assume all overlays or player surfaces are descendants of one transform without checking.

### Why this approach

An app-level boundary is more consistent than scaling each screen separately and less invasive than rebuilding the app inside a second miniature UI tree. It also creates one place to reason about hit testing and mode lifecycle. The transform should only exist while normal app content is displayed; it must not create any work that continues in the background.

## 4. State and transitions

- **Disabled → Enabled:** long-press Library while on ordinary app UI.
- **Enabled → Disabled:** long-press Library again, tap outside the reduced content, or initiate opening any video/trailer.
- **Playback:** one-handed mode is disabled before the player becomes active; player uses the normal, unscaled layout.
- **After playback:** remain disabled. Do not restore the prior enabled state.
- **Navigation between ordinary screens:** retain the enabled state unless one of the disable triggers occurs.
- **App backgrounding/recreation:** no background worker or service is allowed. Persisting the enabled preference across process recreation is not required for the first version; default safely to disabled after a fresh app composition/process if persistence cannot be added without broader architectural change.

If route state can briefly report a player after its UI starts, explicitly disable mode from the video/trailer navigation action as well as using player-route detection as a defensive guard. Avoid asynchronous timing that allows the player to appear scaled for a frame.

## 5. Interaction and visual requirements

- Preserve the existing Library tap and drag-to-select navigation behaviour.
- Add long-press recognition without stealing ordinary taps or breaking the custom navigation bar's drag gesture. Inspect the existing pointer-input gesture detector before choosing an implementation.
- Taps on content within the reduced region must reach the correct controls; do not rely on a visual-only scale.
- Taps in the empty region outside the reduced content dismiss the mode without accidentally activating underlying UI.
- Existing dialogs and popup menus should remain legible and anchored to the correct controls. If a platform dialog or popup cannot safely participate in the transform, document and handle it deliberately rather than allowing misaligned coordinates.
- Preserve current Liquid Glass appearance, shape clipping, backdrop/effect bounds, and hit areas. No new background rendering loop or continuous polling.
- Left/right positioning must not introduce mirroring of content or change navigation order; it changes placement only.

## 6. Video and trailer compatibility

The video/trailer entry path is a hard boundary. Before playback is shown, one-handed mode is turned off and its state is cleared. The player must use its existing normal layout, surface ownership, controls, orientation/insets handling, and playback lifecycle. Leaving playback must not re-enable the feature.

The feature must not alter media selection, playback state, resume position, watched state, or mini-player ownership. If the app can host a mini-player while ordinary browsing continues, verify the intended player route/host behaviour explicitly and ensure one-handed mode does not keep the player surface scaled or transform its touch input.

## 7. Non-goals

- Android system-wide One-handed Mode or changing device settings.
- Operating over other apps, the home screen, notification shade, or system UI.
- Accessibility-service or overlay-based implementation.
- Background/foreground services, persistent notification, polling, or monitoring.
- Automatically restoring one-handed mode after video/trailer playback.
- Redesigning the navigation bar or replacing existing Liquid Glass code.
- Unrelated player, navigation, or layout refactors.

## 8. Error handling and safety

- Fail closed to the normal layout if the mode controller or transform cannot establish valid dimensions.
- Ensure orientation, window-size changes, and insets do not leave content clipped or unresponsive.
- Do not allow outside-region taps to pass through and activate hidden content.
- Avoid duplicate long-press handlers or gesture detectors that compete with the existing navigation bar.
- Do not change product source code until the feature request has explicit repository-maintainer approval, as required by `CONTRIBUTING.md`.

## 9. Verification plan

### Behaviour checks
- Long-press Library enables/disables mode; normal tap still navigates.
- Profile long-press still switches profiles and does not toggle one-handed mode.
- Drag-to-select navigation remains functional.
- Left/right placement works without mirroring or reordering content.
- Outside-region tap dismisses mode and does not trigger underlying controls.
- Scrolling, buttons, navigation, dialogs, popup menus, and Liquid Glass controls work at transformed coordinates.
- Opening each supported video/trailer route disables mode before player presentation.
- Player remains unscaled; controls and playback continue normally.
- Exiting playback leaves one-handed mode disabled.
- Navigation among ordinary tabs retains mode when appropriate.
- Rotation/window-size changes and insets do not break layout or input.
- No background service, notification, overlay permission, polling loop, or unnecessary background activity is introduced.

### Quality checks
- Add focused tests for state transitions and route-triggered disablement where the existing test architecture permits.
- Run relevant unit/common tests and platform build(s) available to the project.
- Manually verify the navigation gestures, transformed hit testing, overlays, Liquid Glass, and player transition on a real Android device/emulator.
- Capture before/after evidence and record exact checks performed. Do not claim tests/builds passed unless actually run.

## 10. Implementation scope candidates

Likely inspection/implementation areas:
- `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/RootTabHost.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/FloatingNavigationBar.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/NavigationBar.kt`
- the existing player route/host and the navigation actions that open videos and trailers

These are investigation targets, not a commitment to modify every file. Final file scope must follow code inspection and remain minimal.
