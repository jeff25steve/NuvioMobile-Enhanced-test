# Playback Stream Selection settings search — design

**Intent.** Extend Settings Search from Playback Player into Stream Selection so users can find and open controls affecting automatic source choice and provider filtering.

**Goals.** Index eight visible controls with localized titles/descriptions, useful aliases, actual subsection breadcrumbs, and exact row anchors. Catalogue visibility must match live player settings and plugin feature flags. Phone/tablet navigation and temporary highlight are preserved.

**Non-goals.** No playback logic or setting value changes, no new dependencies, no search-engine rewrite, no broad UI redesign, no builds or CI.

**Approaches.**
1. Add page-only results — low risk, but users must scroll manually; rejected.
2. Index navigation/switch rows only and leave timeout slider page-only — simpler but inconsistent for a common search target; rejected.
3. Extend shared row anchors with a small generic container for the custom slider — selected because it reuses reveal/highlight behavior without restructuring slider interaction.

**Design.** Add stable keys for Reuse Last Link, Last Link Cache Duration, Auto Stream Selection, Regex Pattern, Stream Selection Timeout, Auto-play Source Scope, Allowed Addons and Allowed Plugins. Use PlayerSettingsUiState and AppFeaturePolicy.pluginsEnabled to match current visibility. Add PlaybackStreamSelection to the strict anchor allow-list with containing lazy item index zero. Preserve actual subsection labels in the shared Playback row catalogue helper. The timeout slider uses SettingsSearchAnchorContainer.

**Constraints.** Common Kotlin source only; Compose Multiplatform 1.13.0-beta01 / Kotlin 2.4.10; no dependency changes. No Gradle tasks, tests, builds, compilation, device sessions or CI without authorization. Commit with [skip ci], never force-push.
