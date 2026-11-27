# Home catalog poster shuffle

**Date:** 2026-11-27  
**Target:** `main-build-testing`  
**Platforms:** Android and iOS, shared Compose Multiplatform

## Intent

Add one optional Home screen setting that changes poster order independently inside each catalog shelf. The catalog shelves themselves must stay in their configured order. The setting is off by default.

When enabled, Nuvio creates a new poster arrangement when a new app process starts and keeps it stable for that session. It does not reshuffle on each recomposition, catalog publication, app resume, or timer tick.

## Current implementation facts

- `HomeScreen` observes `HomeCatalogSettingsRepository.uiState` directly. A setting can therefore change the displayed row order without refetching catalogs or rebuilding the home repository's catalog state.
- Each add-on/server catalog shelf is rendered by `HomeCatalogRowSection`; collection rows use a different component and remain untouched.
- The home catalog preview currently requests at most 18 items. `HomeCatalogRowSection` receives this preview list separately from the full section supplied to View All/navigation.
- `NuvioShelfSection` assigns each poster a stable key using `MetaPreview.stableKey()` (`type:id`). Its Coil display and prefetch paths use the existing shared `posterImageModel` helper and exact artwork request sizes.
- Home catalog definitions are ordered using saved catalog preferences before their rows are emitted. This order is separate from the item order inside an individual row.
- Home catalog settings already have local persistence and a per-profile shared payload. No new storage or networking dependency is needed.

## Approaches considered

1. **Reshuffle whenever a catalog row is composed or updated — rejected.** Home catalogs load and publish progressively. A new `shuffled()` result when a row is recomposed or gains items can move posters already visible and cause unnecessary prefetch work.
2. **Reshuffle every hour using local time — rejected.** It requires a timer/time boundary, changes a row while someone may be browsing, and adds lifecycle/time-zone cases without improving the first-load experience.
3. **One random seed per app process, with a stable rank per catalog/poster — chosen.** The seed is generated lazily in common code and kept only in memory. Each poster gets a deterministic rank based on the session seed, catalog key, and stable poster key. The relative order of already-loaded items stays fixed as more items arrive; a new process generates a new arrangement. This introduces no timer, storage record, network call, or new dependency.

## Goals

- Off by default.
- Label the setting **Shuffle catalog posters**.
- Explain the behavior plainly: **Mix up posters within each catalog. Their order changes when Nuvio restarts, while the catalog shelves stay put.**
- Keep each shelf's position/order unchanged.
- Shuffle only the loaded Home preview posters within each catalog shelf, not collections, Continue Watching, the hero carousel, the full View All catalog, or paginated server responses.
- Keep an arrangement stable throughout one process lifetime, including progressive catalog loads and ordinary recompositions.
- Preserve each poster's object, metadata, stable key, artwork URL, custom-poster fallback, and existing Coil cache identity.
- Keep the setting profile-scoped with backward-compatible local persistence and shared sync.
- Include useful search aliases such as poster order, random posters, mix up posters, and shuffle.

## Non-goals

- No hourly/daily shuffle or background timer.
- No server-side random sort, full-library pagination change, poster reload, prefetch redesign, or new image loader.
- No change to the app's configured catalog shelf order, hidden/disabled rows, manual reorder controls, hero, collection folders, or View All order.
- No new dependency, telemetry, account data, or persisted random seed.

## Implementation decisions

- Add `shuffleCatalogPosters: Boolean = false` to the existing Home settings state and local stored payload.
- Add an optional nullable `shuffle_catalog_posters` field to the shared sync payload. A missing field in an older remote payload preserves the local value; a concrete `false` is an intentional reset/off value.
- Add `HomeCatalogPosterShuffle.kt` with a lazily generated process seed and a pure stable-rank ordering helper. Use a deterministic 64-bit hash/avalanche over the session seed, catalog key, and stable poster key, with the stable key as a tie-breaker. Do not persist the seed.
- In `HomeCatalogRowSection`, use `remember(entries, section.key, shuffleEnabled, seed)` to compute the row display list only when its inputs change. Return the original list unchanged when disabled or when there are fewer than two posters.
- In `HomeScreen`, memoize the capped homepage preview with `remember(section.items)` so unrelated Home recompositions do not recreate the 18-item preview list and trigger another sort.
- Keep the prefetch target list aligned with the displayed order. Existing Coil cache keys, exact sizes, stable LazyRow keys, and artwork request models remain unchanged.
- Add the switch inside Settings > Home screen > Catalogs, before the catalog list, with a dedicated Settings Search anchor and common search terms. Only expose the control/search result when at least one add-on/server catalog exists; collection-only profiles should not see an option that has no effect on their shelves.
- Use the existing Nuvio switch row and current resource localization system; no custom switch or visual treatment.
- Memoize the capped homepage preview from `section.items` at the Home list item so unrelated Home recompositions do not create a new `take(18)` list and trigger another ordering calculation.

## Prior art and guidance

- Apple's Settings HIG recommends good defaults, a small number of clear settings, and putting task-specific options close to the content they affect. The toggle is off by default and lives in the Home catalog settings section.
- Apple's Toggle HIG says to clearly identify the content controlled by a toggle and make on/off states obvious.
- Android's Compose lazy-list guidance says to use stable unique item keys so item state follows the item when list order changes.
- A related Nuvio pull request (closed and not merged) documents the same progressive-load instability caused by re-shuffling a growing list and proposes a stable per-item rank; it includes a regression test that already-loaded items keep their relative order when the pool grows.
- A Jellyfin Android TV random-sort pull request review highlighted missing/duplicate items across later pages when randomizing server pages. Nuvio avoids that class of issue by shuffling only its already-fetched homepage preview list and leaving View All/pagination untouched.
- A Jellyfin community forum thread asks about shuffling a whole home video library with folders. It shows interest in random library discovery, but it does not establish a preference for hourly versus per-launch changes. Social/community evidence is anecdotal; no broad consensus is claimed.

Sources:
- Apple Settings HIG: https://developer.apple.com/design/human-interface-guidelines/settings
- Apple Toggles HIG: https://developer.apple.com/design/human-interface-guidelines/toggles
- Jetpack Compose lazy-list keys: https://developer.android.com/develop/ui/compose/lists
- Nuvio related stable-order investigation (closed PR): https://github.com/NuvioMedia/NuvioMobile/pull/1294
- Jellyfin Android TV random sort review (closed PR): https://github.com/jellyfin/jellyfin-androidtv/pull/4663
- Jellyfin random sort community discussion: https://forum.jellyfin.org/t-is-it-possible-to-shuffle-a-whole-home-video-library-with-folders

## Verification

Tests will cover deterministic output for a seed, a different arrangement for a different seed, catalog-key isolation, order independence from input order, no duplicates/dropped items, preserving existing relative order when the list grows, and the off/default passthrough. Repository host tests cover default-off persistence, local state, shared sync, and legacy payload compatibility. Settings-search tests cover the label and natural aliases.

No Gradle build, test task, device test, or CI run is authorized in this change. Added tests remain unexecuted until build/test verification is authorized.
