# Android startup critical-path split

Date: 12 November 2026
Branch: main-build-testing

## Intent
Show Android Compose Home without waiting for repository initialization Home does not need. Preserve profile-scoped Home content, offline-download routing, feature behaviour, Live TV initial-tab selection and iOS startup.

## Design
Critical before Android setContent: profile cache, watched state, watch progress, Continue Watching preferences, profile-keyed Home snapshot and downloader platform recovery in parallel.

After the first Compose frame: download manifest restore, Library, Live TV, Player, P2P, Server and Meta Screen settings. Notification initialization is moved behind the first Android frame while iOS retains its existing timing.

## Approaches considered
1. Hero/poster/image tuning cannot remove work that blocks setContent.
2. Splitting only StartupPreloader is incomplete because MainAppContent repeats feature ensures in its first composition.
3. Split the preloader and Android root initializers together (chosen); keep Home-critical data before content, remove duplicate Android ensures and run feature loads after first draw. iOS retains eager initialization.

## Safeguards
The default empty download state is not a successfully restored empty manifest. DownloadsUiState exposes a loaded flag and initial offline routing waits for it. LiveTvUiState exposes a loaded flag so initial and native Live TV tab requests wait for real playlist preferences rather than treating the default false value as final. LibraryScreen and feature entry points retain ensureLoaded fallbacks. No Home layout/animation, iOS startup, dependencies, player design, backend or credential formats change.

## Validation
Focused tests cover phase ordering/single-flight and offline fallback readiness. Gradle validation and Android Full debug build are attempted only if repository access allows. No CI or Macrobenchmark.
