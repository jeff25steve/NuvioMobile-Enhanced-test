# Playback settings search row anchors — design

**Intent.** Continue the incremental Settings-search migration recorded in codebase-improvement-notes.md by making frequently searched Playback Player controls directly reachable, rather than stopping at the Playback Player page.

**Goals.** Index actual visible Player preference, external-player, and Android decoder controls with localized titles/descriptions; preserve existing visibility and platform rules; navigate to the exact row on phone and tablet; briefly highlight the row; keep all unmapped results page-only.

**Non-goals.** No settings value changes, no broad settings UI rewrite, no changes to player behavior, no new dependencies, no build/CI/device validation.

**Approaches.**
1. Hard-code more LazyColumn item offsets — rejected because each Playback subpage is rendered as one lazy item containing a regular Column; offsets cannot identify its child rows.
2. Add a nested LazyColumn to every Playback subpage — rejected as an unnecessarily broad layout change with scroll/spacing and state risks.
3. Use existing exact-key row metadata with Compose Foundation BringIntoViewRequester on the actual row — selected because the row is already composed inside the page item and the requester asks all scrollable parents to reveal it without restructuring layouts.

**Design.** Extend the typed anchor registry for Playback Player only. Add catalogue entries only when the corresponding row is visible on the current platform/state. Attach matching keys at the real row declarations. Shared navigation/switch rows use a remembered requester and invoke bringIntoView() only when their own key matches the active search key; existing accent highlight remains time-bounded. Keep the containing LazyColumn item scroll as the first step.

**Constraints.** Keep common Kotlin/Compose Multiplatform APIs; project pins Compose Multiplatform 1.13.0-beta01 and Kotlin 2.4.10. No new dependencies. Do not run builds, tests, compilation, device sessions, or CI without authorization. Preserve phone/tablet route behavior and page-only fallback.