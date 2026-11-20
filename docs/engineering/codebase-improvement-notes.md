# Nuvio Mobile Enhanced — Engineering Improvement Notes

**Repository:** `jeff25steve/NuvioMobile-Enhanced-test`  
**Working branch:** `main-build-testing`  
**Review started from:** `e86df525fa0c633adc753467220592d45bb0a1f9`  
**This continuation rechecked:** `a9f6d930cb6ee436046bf54b9e1e4533e993cf62`  
**This continuation (8 November) rechecked:** `943bdb1c71ad5f4f523f9dd1b61138a608cbeeec`  
**Live TV continuation base:** `75fa9c2d6fcabb104320ff2b41c9d1ee2e9e4217`  
**This continuation (8 November, Android resumable-download audit) rechecked:** `1edc2294d8da643abe0a81e6583036f972ca0dc1`  
**This continuation (8 November, shared/iOS range audit) rechecked:** `6874836063dde2282d301feaaa7cd92d267a643f`  
**Scope:** Android phones/tablets and iPhone/iPad. Shared Kotlin is reviewed with both targets in mind.

These notes are cumulative. Re-check the live branch before using any item: later commits may already contain a fix.

## Engineering rules carried forward

- Treat the target branch and its actual callers as the source of truth; compare upstream, do not copy it blindly.
- Prove ordering, duplicate, freshness, fallback, profile and cancellation semantics before replacing a collection pipeline.
- Prefer bounded traversal when the caller consumes only a small prefix. Prefer a one-time index when the same stable list is searched repeatedly.
- A map/set has construction and memory costs. Do not replace a short list lookup on a tiny collection automatically.
- Avoid blanket conversion to `Sequence`; its iterator/function overhead can outweigh allocation savings, especially for short mobile lists and sorting/flattening pipelines.
- Do not conflate a structural complexity reduction with a measured user-visible speedup. Benchmark representative Android and iOS workloads when builds/benchmarks are authorised.
- Leave website-managed settings, UI/layout, animations, Liquid Glass Editor and navigation untouched during backend/data-path optimisation passes.
- Keep profile boundaries, authentication and cancellation behaviour intact. Avoid stale snapshots where the previous code deliberately rereads live state.

## Dependency context inspected

At the review baseline, `gradle/libs.versions.toml` specified Kotlin 2.4.10, Compose Multiplatform 1.13.0-beta01 and kotlinx.coroutines 1.10.2. No dependency or build configuration changes are part of this work.

## Previously implemented improvement batches

The following work was already present at the review baseline. Do not repeat it without finding a distinct new defect:

- **Library/watch progress:** one-pass identity resolution and progress projections; stable freshness/duplicate behaviour; targeted dirty-key and removal handling; efficient local deletion and embedded-stream candidate resolution.
- **Home hero projection:** reuses poster overlays and collects distinct hero candidates in source order.
- **Watched data:** one-pass watched normalization/snapshot operations; bulk watched-badge completion ID indexing; watched-key parsing without repeated split collections.
- **Anime episode mapping:** exact mapping priority, otherwise greatest earlier episode; first-in-input tie handling; direct regression coverage.
- **Stream ordering and auto-play:** one-pass group bucketing and candidate filtering; preserved preferred/debrid/add-on/plugin priority and binge-only behaviour.
- **Cloud progress lookup:** direct provider/file traversal; first exact file match and unambiguous single-playable-file fallback preserved.

## Documentation structure and update rule

`codebase-improvement-notes.md` is the active summary and recent-cycle log. Detailed records through 19 November 2026 have been moved, not deleted, to [the historical engineering-notes archive](codebase-improvement-notes-history-through-2026-11-19.md). The archive labels old handoff sections as historical so they are not mistaken for current tasks.

Add one section per genuinely new change. For a continuation or hardening of an existing change, update that existing section instead of copying its explanation into another near-duplicate entry. Keep measured results separate from source-level allocation/traversal claims. Before proposing work, check this active log and search the archive for the feature/path.
## Home and detail poster rails — reuse the parent poster-style snapshot (Cycle 94, 20 November 2026)

The high-traffic Home catalogue path already collected `PosterCardStyleUiState` once in `HomeCatalogRowSectionContent` to derive poster prefetch targets and row layout. However, every visible/prefetched `HomePosterCard` called `rememberPosterCardStyleUiState()` again, and the nested `NuvioPosterCard` called it again. The helper resolves a CompositionLocal when present, otherwise it subscribes to `PosterCardStyleRepository.uiState` with `collectAsState()`. The detail poster rail had the same parent snapshot available but did not pass it through.

Changed:
- Added an optional `posterCardStyle: PosterCardStyleUiState?` input to `HomePosterCard` and `NuvioPosterCard`.
- Each component resolves the optional input once and retains the repository-backed fallback for standalone callers.
- `HomeCatalogRowSectionContent` passes its already-collected snapshot through `HomePosterCard` to `NuvioPosterCard`.
- `DetailPosterRailSection` passes its existing screen-level snapshot through the same path.
- Card sizing, image selection, fallback art, labels, watched badges, keys, click handling and the visual layout are unchanged.

This removes the redundant per-card style-state reads/Flow collection fallback from Home catalogue and detail poster rails. The performance claim is limited to reducing repeated state reads and avoiding up to two additional fallback `collectAsState()` calls per composed card when no poster-style CompositionLocal provider is active; no frame-time or scroll benchmark was run, so no millisecond improvement is claimed. Other callers that do not supply a shared snapshot preserve the previous behavior.

Added `scripts/tests/test-home-poster-style-propagation.sh` and wired it into `scripts/tests/test-nuvio-phone-benchmark.sh`. The guard protects both caller pass-through paths, the nested pass-through, and the standalone fallback.

### Evidence and verification boundary

- Android Developers [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices) recommends hoisting shared Flow collection and CompositionLocal reads above lazy layouts and passing resolved values to item composables.
- Android Developers [Where to hoist state](https://developer.android.com/develop/ui/compose/state-hoisting) recommends placing state at the lowest common ancestor and passing immutable state to consumers.
- Context7 was queried for Compose state collection and the exact pinned Coil 3.6.3 Compose API. Coil's official 3.6.3 documentation confirms that `AsyncImage` is restartable/skippable and should remain the normal choice; this change does not replace it or modify image requests.
- The focused shell guard was exercised against an old-structure fixture and failed at the expected missing-parameter assertion (RED). It was then exercised against a fixture representing the updated pass-through structure and passed (GREEN). These checks are isolated fixtures, not execution of the committed regression file against a full local repository.
- The updated production sources, aggregator wiring, test guard and engineering note were re-fetched from GitHub for static review. The committed shell regression was not run from a complete checkout; no Kotlin compilation, Gradle test, build, CI, APK, device run, Macrobenchmark or Perfetto capture was performed.

## Library vertical poster rows — resolve row slices on demand (Cycle 95, 20 November 2026)

The Saved Library's vertical layout stores one sorted, filtered and deduplicated LibraryVerticalProjection.entries list. Its LazyColumn previously called projection.entries.chunked(columns) when declaring content. Kotlin's chunked operation eagerly creates an outer list and one list for every row, even though the LazyColumn virtualizes row composition and only needs a small visible/prefetched window.

Changed:
- Added LibraryVerticalProjection.rowCount(columns) and rowAt(index, columns).
- rowAt returns a bounded subList view of the already-sorted entries; it validates the row index before offset arithmetic and uses Long arithmetic for the offset/end calculation.
- libraryVerticalContent now uses items(count = ...) and resolves only the requested row in the item lambda.
- Preserved the exact first-entry-based row key, existing row content type, row conversion, custom poster handling, watched state, callbacks, ordering and filtering.
- Existing entries remains available for current callers and tests.

This removes the eager outer row-list plus per-row list allocations from the vertical LazyColumn content declaration. It does not remove the flattened projection itself or its sort/filter/dedup work, and it does not change MetaPreview conversion within a composed row. The benefit scales with the number of rows compared with the visible/prefetched row window; it has not been measured on-device, so no frame-time or percentage gain is claimed.

Added scripts/tests/test-library-vertical-row-window.sh, which protects indexed row count/access, stable row-key identity and the removal of eager chunked(columns). It is invoked by scripts/tests/test-nuvio-phone-benchmark.sh. Added unit-test cases for row ordering, final partial rows, invalid indices and invalid column counts.

### Evidence and verification boundary

- The branch's existing LibraryDisplaySettingsTest already protects vertical projection section selection, type filtering and sort ordering. The added unit cases exercise the new row access contract.
- Android Developers [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices) recommends avoiding unnecessary calculation and collection work during composition and virtualized list use. The source change removes full-projection chunking from the lazy item provider.
- Context7 was queried for the pinned Compose Multiplatform API surface; the available result exposes key-based lazy item patterns but did not include the exact 1.13.0-beta01 lazy-list API version. This change uses the existing LazyListScope.items(count, key, contentType) form already present elsewhere in this repository, so it introduces no new framework API or dependency.
- Exa search checked official Android guidance on lazy layout item composition and remember; it also reinforced avoiding micro-optimizing small non-lazy Row loops, so no such changes were made.
- GitHub source was re-fetched after editing for static inspection. No local complete checkout was available to execute the committed unit test or shell regression against production files. No Kotlin compilation, Gradle test, build, CI, APK, device run, Macrobenchmark or Perfetto capture was started.

## Home Continue Watching / Next Up — single-pass completion aggregation (Cycles 96–97, 20 November 2026)

`WatchingState.latestCompletedBySeries` previously created separate progress/watched `groupBy` maps, retained per-content record lists, and invoked a resolver that filtered those groups again and built another completion-marker list to select one maximum. Home Next Up calls this calculation from a `remember` block keyed by progress, hidden-content, watched-history and preference inputs; it is a recomputation on meaningful input changes, not ordinary poster-row scrolling.

Final implementation:
- One `LinkedHashMap<WatchingContentRef, WatchingCompletedEpisode?>` records exact first-seen `(type, id)` order and the best completion for each key. A null value retains a key first seen in incomplete progress without incorrectly including it in the result.
- Progress entries pass through `normalizedCompletion()` and the existing `shouldUseAsCompletedSeedForContinueWatching()` eligibility check. Watched timestamps still pass through `normalizeWatchedMarkedAtEpochMs`.
- The accumulator applies the existing furthest-season/episode or latest-timestamp comparator. Equal comparator values keep the first candidate, matching the original `maxWithOrNull` selection.
- The final non-null result map is populated directly in accumulator order. No separate ordered key set, grouped record lists, per-series completion-marker lists or intermediate key/value-pair list remains.

This removes grouped-list allocations and repeated scans while preserving ordering and completion policy. The source-level saving is concrete; no runtime percentage or frame-time gain is claimed.

Regression coverage includes both selection policies, interleaved series, exact type/ID identity, timestamp normalization, the existing 200-series × 80-episode watched-history case, first-seen order when completion appears later, and omission of incomplete-only keys.

### Evidence and verification boundary

- Kotlin's official [collection grouping documentation](https://kotlinlang.org/docs/collection-grouping.html) describes `groupBy` as constructing per-key lists; the direct map accumulator retains only the required maximum value.
- Exa cross-checked Android Developers [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices). The Home caller already memoizes this derived data by its source inputs, so an additional Compose cache was not added.
- Context7 was used to check Kotlin collection grouping/aggregation documentation. The project pins Kotlin 2.4.10; no newer or version-specific API and no dependency were introduced.
- The updated source and regression tests were reviewed remotely. They have not been executed: no complete local checkout is available, and no Gradle test/build, CI, device run or Macrobenchmark was authorised.
## Cloud Library search — filter provider lists without flattening (Cycle 98, 20 November 2026)

`cloudLibraryContent` is the `LazyListScope` content builder for the Cloud Library. The search query is an input to the library screen, so each query update recomputes its projection. Previously the path materialized `CloudLibraryUiState.items` through `providers.flatMap`, then allocated additional provider-filtered and type-filtered lists before producing the text-filtered result. `reconcileCloudLibrarySelectedItemKey` separately accessed the same flattening getter to validate the current selection.

Changed:
- Added `projectCloudLibraryItems`, which reads each provider's existing item list directly. Its first pass collects available types from items whose own `providerId` matches the effective provider; its second pass applies the same item-level provider predicate before building the final filtered result.
- Kept available-type ordering by enum ordinal, provider/item encounter order, stale selected-type fallback to no type filter, trimmed query behavior, case-insensitive item-name matching and nested-file-name matching.
- Changed selected-item reconciliation to search provider item lists directly, avoiding a separate flattened list allocation.
- Kept `CloudLibraryUiState.items` available for existing callers; this hot UI path no longer depends on it.
- Added common regression cases for provider isolation/order, item-name and file-name search, unavailable selected types, valid type filtering, selection reconciliation, null-key no-iteration behavior and item-level provider ID mismatch.

This removes the full flattened catalog copy and intermediate filter lists from the query-update path, and removes the second flattened copy used by selection validation. The final `filteredItems` list and sorted type list remain because the lazy list and toolbar consume them. No cache keyed by the flattened list was added: that getter creates a fresh list, making it a poor stable memoization key. No user-visible filtering or playback behavior was intentionally changed.

### Evidence and verification boundary

- Kotlin's official [collection filtering documentation](https://kotlinlang.org/docs/collection-filtering.html) and [collection transformations documentation](https://kotlinlang.org/docs/collection-transformations.html) describe collection operations that return new lists; Context7 also checked Kotlin grouping/collection documentation. The implementation uses direct nested traversal and one destination list rather than a chain of intermediate list results.
- Exa checked Android Developers [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices), including guidance to keep expensive collection transformations out of frequently recomposed UI and to avoid unnecessary `remember` keys for large collections.
- The dependency catalog pins Kotlin 2.4.10 and Compose Multiplatform 1.13.0-beta01. This change uses only existing Kotlin collection operations and does not add framework APIs or dependencies.
- The added `CloudLibraryFilterTest` and existing Cloud Library tests have not been executed; source and test files were statically reviewed from GitHub. No Gradle test/build, CI, device run or benchmark was started. This is a structural allocation/traversal improvement, not a measured performance result.

## Cloud Library — skip selected-item scan when no item is selected (Cycle 99, 20 November 2026)

After Cycle 98, the normal Cloud Library browse state still called `filteredItems.firstOrNull { it.stableKey == selectedCloudItemKey }` on every filter recomposition. `selectedCloudItemKey` is nullable and defaults to null in the Library screen. With no selection, the predicate could never match, but it still traversed the full filtered list and evaluated the interpolated `stableKey` getter for each item.

Changed:
- Added `findCloudLibraryItemByKey`, which returns immediately when the selected key is null and otherwise retains the original first-match lookup.
- Replaced the unguarded `firstOrNull` call in `cloudLibraryContent` with the helper.
- Added a common test whose list throws if accessed, proving the null-key path must return without reading or iterating the list; retained assertions for matching and unknown non-null keys.

This removes an avoidable O(n) scan and per-item stable-key string construction from the ordinary no-selection browse path. It does not change the selected-item path or list ordering. No time/percentage gain is claimed.

### Verification boundary

- The regression source was added before the production helper, then both were statically re-read from GitHub after integration. The common tests were not executed because no complete local checkout is available; no Gradle tasks, build, CI or benchmark were run.


## Search catalogue projections — ordered single-pass builders (Cycles 100–101, 20 November 2026)

The Search repository built per-addon request/source lists with repeated eager collection transformations: mapNotNull created addon/manifest pairs, flatMap flattened each addon's catalog results, and nested filter/map calls created additional lists. Search request projection runs after settled query changes; Discover source projection runs when its source configuration is refreshed.

### Cycle 100 — Search request projection

- Replaced the addon/manifest pair projection, per-addon capability filter and mapped-list flattening with one buildList and nested source-order loops.
- Preserved missing-manifest skipping, the exact supportsSearch() predicate, query propagation, pagination support, and addon/catalog encounter order.
- Exposed the pure builder internally so the existing SearchRequestStateTest can assert the actual projection without initiating network requests.

### Cycle 101 — Discover source projection

- Replaced the corresponding addon/manifest pair and per-addon filter/map/flatten chain with one buildList and nested source-order loops.
- Preserved missing-manifest skipping, the exact supportsDiscover() predicate, stable source keys, addon display metadata, genre options/required state, pagination support, and source order.
- Extended the same request-state test suite to cover supported/unsupported catalogs, required genre options and empty required-genre rejection.

Both changes reduce temporary collection materialization while retaining the final list consumed by the repository. They do not change network concurrency, partial search publication, cancellation/generation handling, cache keys, request signatures or UI rendering. No runtime time, allocation count or user-visible speedup is claimed without profiling.

### Evidence and verification boundary

- Context7 checked the Kotlin standard-library collection builder and transformation documentation for the project's pinned Kotlin 2.4.10 line. The implementation uses established buildList and direct nested loops; no experimental API or dependency was added.
- Exa cross-checked the official [Kotlin collection transformations](https://kotlinlang.org/docs/collection-transformations.html), [collection builders](https://kotlinlang.org/docs/constructing-collections.html) and [Sequences](https://kotlinlang.org/docs/sequences.html) guidance. The first two explain that transformations create new collections and that builders support direct population; the Sequence guide notes its lazy pipeline overhead can matter for smaller collections, so this change does not blanket-convert the paths to sequences.
- Regression tests were committed before the production builder changes. They cover stable order, query and addon metadata, search/discover capability filters, pagination and genre metadata. They were not executed: no Gradle test, build, CI, device run or benchmark was started.
- The final production source and test additions were re-fetched from the remote branch and checked for the new single-pass builders, absence of the old builder chains, and retained capability predicates. This is static source review, not compilation or runtime verification.