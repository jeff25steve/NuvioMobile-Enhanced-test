# Startup logo animation preference — design

Date: 28 November 2026  
Target: main-build-testing

## Intent

Let people skip Nuvio's decorative animated logo intro when the app opens, while retaining the normal startup flow. The preference is off by default and discoverable through Settings search.

## User-facing copy

- **Setting:** Skip startup logo animation
- **Description:** Skip the animated Nuvio logo when the app opens. Profile selection and loading still work as usual.
- **Location:** Settings → Appearance → Display
- **Search terms:** skip intro animation, skip logo animation, splash screen, startup animation, app launch animation, opening animation

## Behavior contract

- Default is off; existing installs without the stored key retain the original intro.
- When on, render the Nuvio wordmark at its normal final size without fade, scale, animated glow or drifting light.
- The startup gate must not wait for the decorative intro when the setting or platform Reduce Motion preference says to skip it.
- Authentication, profile cache restoration, network work, profile listing, remembered-profile logic, profile selection, PIN/biometric checks and profile-to-main transitions remain exactly as before.
- The setting is installation/device-local, not profile-scoped and not included in profile sync or settings export.
- Respect the system animation accessibility setting: Android API 26+ uses the public ValueAnimator.areAnimatorsEnabled() API; iOS uses UIKit UIAccessibility.isReduceMotionEnabled. On older Android API 24–25, retain the default behavior because the public areAnimatorsEnabled API is unavailable.
- Do not change or suppress the native OS splash screen. The toggle controls only Nuvio's Compose intro after the native splash is dismissed.
- No dependency, manifest permission, hidden API, system setting write, timer, background work or additional image work.

## Architecture

A pure common policy combines the app preference with platform Reduce Motion. Platform actuals read only the public OS accessibility/animation state. ThemeSettingsRepository exposes the global boolean and persists it through existing private Android SharedPreferences and iOS NSUserDefaults storage. AppGate uses the effective preference to initialize the intro hold as already complete; AppIntroContent renders a static wordmark and signals completion when animation is skipped.

## UI and accessibility

Apple's Motion guidance says motion should be purposeful, optional and brief, and people should not be forced to wait for animations to finish. Android's SplashScreen guidance governs the OS splash separately and recommends animated logo motion of no more than one second. The in-app control is plain language and searchable; the OS accessibility preference takes precedence over the optional app preference.

Direct recent Reddit consensus about disabling media-app logo intros was not found in the research sweep; this design does not claim a universal preference. Maintained Jellyfin Android uses the Android platform splash and avoids requiring a separate branded Compose intro.

## Files

- Modify AppGate.kt and AppIntroContent.kt: skip decorative intro only and avoid holding the auth/profile gate for it.
- Add the Android and iOS platform actuals for the common Reduce Motion check.
- Modify ThemeSettingsRepository / ThemeSettingsStorage contract and Android/iOS storage implementations.
- Modify AppearanceSettingsPage and SettingsSearch; add English default resource strings.
- Add common policy and search-anchor regression tests.
- Append the compact decision to existing engineering notes and save this spec and plan.

## Sources

- Apple HIG Motion: https://developer.apple.com/design/human-interface-guidelines/motion
- Apple Accessibility: https://developer.apple.com/design/human-interface-guidelines/accessibility
- Android SplashScreen: https://developer.android.com/develop/ui/views/launch/splash-screen
- Android ValueAnimator: https://developer.android.com/reference/android/animation/ValueAnimator
- Apple UIAccessibility Reduce Motion: https://developer.apple.com/documentation/uikit/uiaccessibility/isreducemotionenabled
- Maintained Jellyfin Android project: https://github.com/jellyfin/jellyfin-android

## Validation boundary

Focused common tests are authored but unexecuted. No Gradle task, compilation, build, CI, benchmark or device test is run without authorization. Android API 24–25 fallback and iOS UIKit interop remain source-reviewed only until authorized builds.
