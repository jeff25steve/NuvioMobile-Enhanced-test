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
- The final production source and test additions were re-fetched from the remote branch and checked for the new single-pass builders, absence of the old builder chains, retained capability predicates, expected constructor fields and regression assertions. This was an author static self-review; no subagent-dispatch tool was available. It is not compilation or runtime verification.

## MetaDetails parser collection projections — ordered name merge and season poster maps (Cycles 102–103, 20 November 2026)

Two independent MetaDetails parsing paths created avoidable intermediate collections while building final consumer-facing values. These changes keep the parser API and parsed values unchanged.

### Cycle 102 — director and writer name merge

- Replaced each `(topLevel + extra + linked).map(trim).filter(notBlank).distinct()` chain with one shared ordered merge helper using a membership set and one result list.
- Preserved group precedence (top-level, app extras, linked credits), first-seen order, trimming, blank exclusion and case-sensitive duplicate handling.
- Added parser-level coverage for exact duplicates, case-only distinctions, whitespace and ordering across all three source groups.

### Cycle 103 — season poster maps

- Replaced the episode-season `mapNotNull/filter/distinct/sorted` chain with one `buildSet` traversal followed by a single sort; count positive seasons without materializing a separate filtered list.
- Build positional poster maps directly rather than creating indexed pairs and converting them with `toMap`.
- Build keyed poster maps directly with `buildMap`, preserving invalid-entry skipping and last-value-wins when different numeric key strings parse to the same season number.
- Added coverage for numeric key aliases, overwrite precedence, invalid keys, blank/null/object values and keyed-map precedence. Existing positional tests continue to cover specials, sparse seasons, duplicate episode seasons and null poster slots.

These are structural allocation reductions in the metadata parsing path; no parsing-time or memory percentage is claimed without measurement. No public API, JSON field precedence, player behavior, UI or dependency was changed.

### Evidence and verification boundary

- Context7 checked Kotlin standard-library buildSet/buildMap semantics and insertion-order behavior against the Kotlin documentation. The project pins Kotlin 2.4.10; only established common standard-library APIs are used.
- Exa cross-checked official [Kotlin collection transformations](https://kotlinlang.org/docs/collection-transformations.html), [collection builders](https://kotlinlang.org/docs/constructing-collections.html), and [collection types](https://kotlinlang.org/docs/collections-overview.html). Kotlin documents transformation results as new collections; builders support direct population, and the default linked set/map implementations preserve insertion order.
- Regression tests were added before their corresponding production changes, but were not executed because the instruction forbids builds/CI and no test execution was authorized. Consequently, red/green behavior and compilation remain unverified.
- Final source/test files and commit diffs were re-fetched from GitHub and statically reviewed. No independent reviewer agent was available in this session; this was author self-review only.

## Search history and Library provider rank projections — bounded traversal and direct map construction (Cycles 104–106, 20 November 2026)

Three small collection paths still performed work that their consumers did not need. These changes preserve public APIs, ordering and selection semantics; they only remove avoidable traversal or intermediate collection materialization.

### Cycle 104 — stop search-history normalization at its output limit

- `normalizeSearchHistoryEntries` previously continued iterating the full decoded history after collecting the requested number of unique entries. The result could no longer change once the limit was reached.
- Changed the traversal to stop immediately at the limit.
- Added a regression input whose third element throws, proving that a two-entry result does not read beyond its required prefix.
- The configured repository limit remains 10; this is a bounded-work improvement for oversized or legacy stored payloads, not a claim that ordinary ten-entry history is a major hotspot.

### Cycle 105 — build the updated search history without concatenating lists

- `applySearchHistoryEntry` previously allocated `listOf(query) + current` and then passed that temporary list through normalization.
- It now appends the trimmed query first and traverses existing entries directly into the final bounded result, using the same case-insensitive duplicate check and minimum query length.
- Added a regression input that throws if the existing history is read beyond the number of entries needed to fill the result.
- Preserved query-first order, trim behavior, case-insensitive de-duplication, limit handling and the existing immutable return value.

### Cycle 106 — construct provider rank maps directly

- The combined provider-order Flow previously converted each emission with `mapNotNull { ... }.toMap()`, materializing an intermediate pair list before creating the final rank map.
- Replaced that conversion with `buildMap` and a direct loop. Null provider results remain omitted; non-null entries retain the source order.
- Extended the horizontal-list regression to assert provider-key encounter order as well as independent rank values for each list.

### Evidence and verification boundary

- Context7 checked the Kotlin standard-library builder and traversal documentation for the pinned Kotlin 2.4.10 line. The changes use established `buildList`/`buildMap` APIs and direct iteration; no new dependency or framework API was introduced.
- Exa cross-checked the official [Kotlin collection builders](https://kotlinlang.org/docs/constructing-collections.html), [collection operations](https://kotlinlang.org/docs/collection-operations.html), and [buildList API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/build-list.html). The documentation confirms that builders populate a destination collection and that ordinary transformation operations return new collections.
- Regression assertions were committed before their corresponding production changes. They were not executed: no Gradle tests, builds, benchmarks, device runs or CI were started.
- Production and test files plus this note were re-fetched from GitHub for static review. This was author self-review; no independent reviewer agent was available. No runtime performance percentage or user-visible latency claim is made.

## Home shuffle and Continue Watching data projections — indexed progress and fused filtering (Cycles 107–108, 20 November 2026)

This cycle pair changes only common Kotlin data-processing helpers. No Compose UI source, layout, component, modifier, styling, navigation, interaction or presentation model was changed.

### Cycle 107 — index episode progress once for Home shuffle resume selection

- `applyHomeShuffle` previously scanned the complete progress list separately for each resumable Home item using `progress.filter(...).maxOfOrNull(...)`.
- Built a lookup of the latest update timestamp per exact parent metadata ID, season and episode in one traversal, then used constant-time map lookups while selecting the latest resumable item per show.
- Preserved the prior missing-match default of zero, maximum timestamp behavior (including negative timestamps), parent/season/episode identity, group encounter order and the existing `maxBy` tie behavior.
- Added a regression test with an instrumented progress list. It asserts the same latest episode is selected and that progress is traversed once rather than once per candidate item.

### Cycle 108 — remove intermediate lists from Continue Watching result projection

- The final projection previously built an identity list before converting it to a set, then created two successive filtered lists.
- Changed identity collection to `mapTo(mutableSetOf())` and combined identity membership with the existing active-progress predicate in one filter pass.
- Kept the domain selector, identity keys, completion/in-progress predicate, and descending `lastUpdatedEpochMs` sort unchanged.
- Added coverage combining completed and active entries to pin the visible active-result order.

### UI and behavior boundary

- No UI files were edited. No composable, component, layout, style, animation, navigation or user interaction code was touched.
- The intended user-facing item selection and ordering remain the same; these are internal data projection changes only. They do not add or remove UI elements or alter their appearance.
- No measured frame-time, latency or allocation percentage is claimed.

### Evidence and verification boundary

- Context7 checked Kotlin collection filtering and destination collection operations against the project's pinned Kotlin 2.4.10 line. Exa cross-checked the official [Kotlin collection filtering](https://kotlinlang.org/docs/collection-filtering.html), [collection operations](https://kotlinlang.org/docs/collection-operations.html), and [filterTo API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/filter-to.html) references.
- Regression tests were committed before their production changes. They were not executed because no test/build execution was authorized. Compilation and runtime behavior therefore remain unverified.
- The production and test sources and this note were re-fetched from GitHub for static review. This was author self-review; no independent reviewer agent was available.

## MetaDetails embedded proxy headers — direct map construction (Cycle 109, 20 November 2026)

`JsonObject.stringMap()` is used to parse request and response proxy headers for embedded video streams. It previously built a temporary list of key/value `Pair` objects with `mapNotNull`, then copied those pairs into the final map with `toMap`.

Changed:
- Populate the final map directly with `buildMap` and `put`.
- Preserve the original primitive-only acceptance rule, whitespace-only value exclusion, untrimmed header values, JSON entry encounter order and exact header keys.
- Added a parser regression case covering both request and response headers with multiple entries.

This removes the intermediate mapped pair list and per-entry pair objects from this proxy-header parsing path. It does not change network request behavior, header normalization or stream selection. No runtime timing or allocation percentage is claimed.

### Evidence and verification boundary

- Context7 checked Kotlin's standard-library `buildMap` and map insertion semantics for the project's Kotlin 2.4.10 dependency line. `buildMap` returns a read-only map populated directly; the default linked map preserves insertion order.
- Exa checked official Kotlin [collection builders](https://kotlinlang.org/docs/constructing-collections.html), [collection transformations](https://kotlinlang.org/docs/collection-transformations.html) and [buildMap API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/build-map.html). The docs confirm that `mapNotNull` creates a result collection, `toMap` consumes pairs, and `buildMap` supports direct population.
- Regression coverage was added before the production change. No tests, Gradle tasks, builds, CI, device runs or benchmarks were run, per the explicit authorization boundary. Compilation and runtime behavior remain unverified.
- The final production source and regression test were re-fetched from GitHub and statically inspected. This is author review, not an independent review or runtime verification.

## MetaDetails CSV name parsing — fuse trim and blank filtering (Cycle 110, 20 November 2026)

`JsonObject.stringListOrCsv()` handles comma-separated director, writer and cast values. Its CSV branch previously called `split`, `map(String::trim)` and `filter(String::isNotBlank)`, creating two additional intermediate lists after tokenization.

Changed:
- Retained the same comma splitting, then trim and blank-check each token directly into one `buildList` destination.
- Preserved token order, case, trimming, empty-token removal and the existing array-valued JSON path.
- Reused the existing parser regression coverage for comma-separated directors, writers and cast, including whitespace and blank tokens; no duplicate test fixture was added.

This removes the intermediate trimmed and filtered lists from CSV metadata parsing. The split token list and final result remain; this is a bounded allocation reduction, not a claim of zero-allocation parsing or measured latency improvement.

### Verification boundary

- Context7 and Exa evidence recorded in Cycle 109 applies to the same pinned Kotlin 2.4.10 collection-builder semantics; this change introduces no new API or dependency.
- Existing parser regression cases were inspected remotely but not executed. No tests, Gradle tasks, builds, CI, device runs or benchmarks were started.
- The updated source and active note were re-fetched from GitHub for static review; compilation and runtime behaviour remain unverified.

## StreamParser proxy headers — direct map construction (Cycle 111, 20 November 2026)

`StreamParser` parses provider-supplied proxy request and response headers for selectable stream results. Its `JsonObject.stringMap()` duplicated the same `mapNotNull` → `Pair` list → `toMap` allocation pattern removed from `MetaDetailsParser` in Cycle 109.

Changed:
- Populate the returned map directly with `buildMap` and `put`, removing the intermediate pair list.
- Preserve primitive-only values, whitespace-only exclusion, untrimmed header values, exact key casing and source entry order.
- Added a focused regression case covering multiple headers, blank values, null values and nested-object values, including the retained key order.
- Existing tests for proxy response headers and forcing `notWebReady` remain unchanged.

This applies the same verified collection-builder approach to the separate stream-provider parsing path. It does not alter stream eligibility, proxy behavior, network requests or header normalization. No runtime performance percentage is claimed.

### Evidence and verification boundary

- Context7 resolved the Kotlin documentation and checked `buildMap` common standard-library semantics for the project's Kotlin 2.4.10 line.
- Exa cross-checked official Kotlin [buildMap API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/build-map.html), [collection transformations](https://kotlinlang.org/docs/collection-transformations.html), [mapNotNull API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/map-not-null.html) and [collection builders](https://kotlinlang.org/docs/constructing-collections.html). These confirm direct builder population and that `mapNotNull` returns a separate list.
- Regression coverage was added before the production change. No tests, Gradle tasks, builds, CI, device runs or benchmarks were executed, per the authorization boundary; compilation and runtime behavior remain unverified.
- Final source, test and existing engineering notes were re-fetched from GitHub and statically reviewed. This was author self-review; no independent reviewer agent was available.

## MetaDetails app_extras cast CSV — direct person-list construction (Cycle 112, 20 November 2026)

The primitive-string branch of `JsonObject.people("cast")` parsed comma-separated `app_extras.cast` values with `split → map(trim) → filter(notBlank) → map(MetaPerson)`. This materialized three transformation results before returning the final people list.

Changed:
- Retain the same comma tokenization, then trim and reject blank tokens while adding `MetaPerson` values directly to one `buildList`.
- Preserve encounter order, case, duplicate entries, trimming and blank exclusion.
- Added a parser regression case for leading/trailing whitespace, empty tokens and duplicate cast names.

This removes the intermediate trimmed-string, filtered-string and mapped-person lists from this CSV path. The split token list and final result remain; no runtime latency or allocation percentage is claimed. JSON-array cast parsing and all director/writer paths are unchanged.

### Evidence and verification boundary

- Context7 checked Kotlin standard-library collection operations and the stable `buildList` builder. The project remains pinned to Kotlin 2.4.10; no dependency or API change was introduced.
- Exa cross-checked the official Kotlin [buildList API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/build-list.html), [collection builders](https://kotlinlang.org/docs/constructing-collections.html) and [collection transformations](https://kotlinlang.org/docs/collection-transformations.html). The docs confirm builder population and that map/filter transformations return new collections.
- Regression coverage was committed before the production change. No tests, Gradle tasks, builds, CI, device runs or benchmarks were executed, per the authorization boundary; compilation and runtime behavior remain unverified.
- Production, test and note files were re-fetched from the target branch for final static review. This is author self-review; no independent reviewer agent was available.

## MetaDetails cast merge — avoid temporary top-level person projection (Cycle 113, 20 November 2026)

The cast parser converted top-level cast names to a temporary `List<MetaPerson>` with `map`, then immediately traversed that list inside `mergePeople`. The merge helper only needs each name to create or enrich the final keyed result.

Changed:
- Pass the already-parsed top-level cast names directly into `mergePeople`.
- Append app-extras people, top-level names and linked cast entries in the original precedence order.
- Keep the existing case-insensitive merge key, first-seen output order, normalized display name, and first-non-null role/photo enrichment behavior.
- Added a regression test asserting order, case-insensitive duplicate collapse, and preservation of app-extras role/photo metadata.

This removes one intermediate mapped list and its temporary top-level `MetaPerson` instances. The original names list and final merged result remain. No output, metadata precedence, or public API change is intended; no runtime speedup is claimed.

### Evidence and verification boundary

- Context7 checked Kotlin 2.4.10 collection transformation semantics and iteration-order behavior. Exa cross-checked the official [collection transformations](https://kotlinlang.org/docs/collection-transformations.html) and [collection builders](https://kotlinlang.org/docs/constructing-collections.html) documentation.
- The new regression test was committed before the production change. No tests, Gradle tasks, builds, CI, device runs or benchmarks were executed; compilation and runtime behavior remain unverified.
- Source, test and notes were re-fetched from GitHub for static self-review. No independent reviewer agent was available.

## Addon manifest resources — single-pass parse and blank-name filtering (Cycle 114, 20 November 2026)

`AddonManifestParser.resources()` previously mapped every manifest resource into an `AddonResource` and then filtered blank names, creating an intermediate list that was immediately discarded.

Changed:
- Parse each resource and append it directly to a `buildList` only when its name is nonblank.
- Preserve primitive resource handling, object resource parsing, inherited default types and ID prefixes, explicit per-resource overrides, and original resource order.
- Added parser coverage for primitive and object resources, blank primitive-name filtering, default metadata inheritance and explicit overrides. Object resources with missing or blank required names continue to fail validation before filtering.

This removes the intermediate all-resources list while retaining the final filtered list. It does not change manifest acceptance rules or addon capabilities. No runtime parsing-time or allocation percentage is claimed.

### Evidence and verification boundary

- Context7 checked Kotlin 2.4.10 collection builder and transformation semantics. Exa cross-checked the official [buildList API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/build-list.html), [collection builders](https://kotlinlang.org/docs/constructing-collections.html) and [collection transformations](https://kotlinlang.org/docs/collection-transformations.html).
- Regression coverage was added to `main-build-testing` after the production change because the initial file-create request used the repository default branch; that misplaced test file was immediately removed from `enhanced`, then recreated on the target branch with the corrected primitive-only blank-name fixture. No tests, Gradle tasks, builds, CI, device runs or benchmarks were executed; compilation and runtime behavior remain unverified.
- Production source, tests and this existing engineering note were re-fetched from GitHub for static self-review. No independent reviewer agent was available.


### Startup Profile and benchmark review — 20 November 2026

Reviewed the live Android Baseline Profile and startup benchmark path across the producer module, app build variants, Java profile journeys, shared readiness helper, desktop runner, LADB runner, manual-only GitHub Actions workflow and existing regression guards.

- The profile collection split is consistent: launcher startup contributes to both the Startup Profile and Baseline Profile; the separate Home browse journey contributes Baseline Profile rules only. The R8 benchmark variant is isolated from production under the `.benchmark` application ID, and profile capture uses the matching non-minified variant.
- The benchmark implementation has three cold-start arms, each configured for ten measured iterations: no compilation; partial compilation without the profile and three warm-up iterations; and partial compilation requiring the packaged profile. Both StartupTimingMetric and ArtMetric are requested. The phone runner already validates all three result names, TTID/TTFD data and per-iteration output.
- Hardened the desktop runner's result gate so each flavor log must contain all three JUnit case names and at least three occurrences of each expected TTID, TTFD and ART metric before the run is summarized.
- Corrected the workflow-generated `START-HERE.txt`, which previously described only the no-compilation and profile-enabled arms. It now documents the middle partial-without-profile arm and three warm-up launches, matching the source and LADB runner.
- Updated the existing Baseline Profile regression guard to protect desktop-runner case/metric validation and the generated phone instructions. No separate guide was added.
- Added a source guard requiring `SentryInitializer.start(application)` exactly once inside the measured `Nuvio.Startup.preSuper` block and before `super.onCreate`, preserving both the trace boundary and early crash-capture ordering.
- Wired `test-android-startup-initialization.sh` into the existing `test-nuvio-phone-benchmark.sh` aggregator. The initialization guard (including the app-icon and startup-trace checks) was previously present but not reached by that aggregate path; the manual workflow invokes the aggregator, so this closes a real regression-coverage gap.
- Corrected the Startup Insights version guard to check `androidx-benchmark-macro = "1.6.0-alpha02"` rather than the separate Baseline Profile Gradle plugin alias `androidx-benchmark = "1.5.0"`. No dependency versions were changed.
- Rechecked the pinned UI Automator 2.4.1 scroll-to-element contract and Activity Compose `ReportDrawnWhen` behavior. The Home browse wait and Activity TTFD bridge use the documented APIs; no source change was warranted.
- Confirmed the main-manifest `<profileable android:shell="true" />` is intentional for release-like profiling: Android's official Macrobenchmark and profiling guidance requires a profileable target and explicitly describes the low-overhead release configuration. It was not removed from production variants.
- The active notes and historical archive remain the intentional summary/archive split. The older startup incident log, design documents and implementation plans have distinct historical purposes; no new guide or duplicate notes file was created, and no historical record was deleted.

### Review and verification boundary

- Checked exact pinned versions: AGP 9.4.0, Gradle 9.8.2, Baseline Profile plugin 1.5.0, Macrobenchmark 1.6.0-alpha02, UI Automator 2.4.1, ProfileInstaller 1.4.1 and Activity 1.12.2.
- Cross-checked the API and metric contracts with Context7 and current Android Developers documentation/release notes. Macrobenchmark's StartupTimingMetric supplies TTID and TTFD; its JSON stores per-iteration runs and median/minimum/maximum statistics. The benchmark uses physical-device cold launches and the profile is required explicitly in the profile arm.
- Re-fetched all changed files and the current branch state after the edits for static review. No build, Gradle task, shell test, CI, instrumentation, profile collection, device benchmark or Perfetto capture was run, as requested. The edits are therefore statically reviewed but not executed or compiled. No runtime startup improvement is claimed.
- This was a source-level self-review; no independent reviewer agent was used.

## Addon manifest logo URLs — preserve origin for root-relative and scheme-relative paths (Cycle 115, 20 November 2026)

The manifest parser resolves addon-provided logo references against the manifest URL. Its previous root-relative branch derived the host by splitting the already-truncated manifest directory at the first slash. For a normal URL such as `https://addons.example.test/catalogs/addon/manifest.json`, that split returned `https:` rather than the origin, so `/images/logo.png` was incorrectly joined beneath the manifest directory. Scheme-relative references were also always forced to HTTPS instead of inheriting the manifest scheme.

Changed:
- Extract the manifest origin from the scheme/authority boundary and the directory from the manifest path, after removing query and fragment components from the base URL.
- Resolve root-relative paths against the origin, ordinary relative paths against the manifest directory, and scheme-relative paths with the manifest's own scheme.
- Preserve already-absolute HTTP(S) and data URLs unchanged; no URL library, dependency or public API was added.
- Added regression cases for a nested manifest with a query string and a root-relative logo, plus an HTTP manifest with a scheme-relative CDN logo.

This corrects concrete logo URL construction without changing the manifest transport URL or the resource/catalog parsing rules. The implementation intentionally covers the reference forms already handled by this helper; it does not claim to implement every RFC 3986 dot-segment normalization case.

### Evidence and verification boundary

- Exa checked RFC 3986 URI-reference semantics for root-relative and scheme-relative references. A scheme-relative reference inherits the base URI scheme; a root-relative reference replaces the path while retaining the authority.
- Context7 was consulted for Ktor URL handling, but its available documentation did not expose a direct common URL-reference resolution API for the exact pinned Ktor 3.4.1. No Ktor API was introduced; the targeted fix uses the existing string-based helper and no new dependency.
- Regression tests were committed before the production change. No tests, Gradle tasks, builds, CI, device runs or benchmarks were executed. The new cases and source were re-fetched for static review; compile/runtime verification remains outstanding.

## Stream provider parsing — ignore malformed optional JSON field types (Cycle 116, 20 November 2026)

`StreamParser` consumes JSON supplied by external addons. Its optional-field helpers used kotlinx.serialization's `jsonPrimitive` extension directly. That extension casts and throws when a field is actually a `JsonObject` or `JsonArray`; one malformed optional field (for example an object-valued stream name or array-valued file index) could therefore abort parsing of the entire provider response, including otherwise valid sibling metadata.

Changed:
- Read optional scalar fields through safe `as? JsonPrimitive` checks before accessing string, integer, long or boolean content.
- Apply the same safe primitive check to string and integer arrays, skipping only malformed elements while retaining valid entries in their original order.
- Preserve existing handling of valid primitive values, numeric-string fallback for integer/long fields, blank string filtering, proxy-header parsing and stream selection.
- Added a regression case containing object/array values across top-level stream fields, client-resolve metadata, parsed season/episode arrays and behavior hints, while asserting the valid stream and valid sibling values remain available.

This is a defensive parsing improvement for untrusted addon responses: malformed optional metadata no longer makes the whole stream list fail. It does not weaken validation of the required top-level JSON object or alter playback eligibility for valid responses.

### Evidence and verification boundary

- Context7 resolved kotlinx.serialization documentation, but the exact pinned 1.10.0 version was not listed in the available version set (1.9.0 and 1.11.0 were available). The official Kotlin JSON-elements documentation confirms that `jsonPrimitive` throws on a mismatched element type and that `JsonPrimitive`, `JsonArray` and `JsonObject` are distinct element types. The fix uses only stable type checks and casts; no dependency or serialization configuration changed.
- Exa cross-checked the official [Kotlin JSON elements documentation](https://kotlinlang.org/docs/serialization-json-elements.html) and kotlinx.serialization API references for `JsonPrimitive`.
- Regression coverage was committed before the production helper changes. No tests, Gradle tasks, builds, CI, device runs or benchmarks were executed. The source and regression file were re-fetched for static review; compilation and runtime behavior remain unverified.

## Catalog page loads — prevent cancelled and superseded requests from publishing (Cycle 117, 20 November 2026)

The catalog repository runs page requests on `Dispatchers.Default`, but previously identified the active operation only by `CatalogRequest` equality. A forced refresh of the same catalog keeps that key unchanged. The previous job is cancelled, but its `runCatching` converted the cancellation into a failure result; its failure handler could then pass the same-key check and overwrite the new request's loading/results state. The internal Library observer also checked only the request key before and after poster settings were loaded, leaving a same-key replacement window. The repository's active request and job references were plain mutable fields shared between caller and Default-dispatcher code.

Changed:
- Added request ownership checks that require both the immutable request key and the exact active `Job` identity. A same-key replacement can no longer inherit the previous job's permission to publish.
- Start jobs lazily, publish the new job reference before starting it, and clear the active reference before cancelling the previous job. This closes the window where a cancelled job could still appear current.
- Recheck ownership after suspending poster-settings loading and immediately before publishing the page or Library state.
- Rethrow `CancellationException` from page failure handling rather than converting cancellation into a visible catalog failure.
- Store the active job and request in AtomicFU atomic references. This makes the cross-dispatcher ownership reads/writes visible and atomic; the pinned catalog already includes AtomicFU 0.32.1, so no dependency was added.
- Added focused ownership tests for same-key/different-job replacement and changed request keys.

The correction affects cancellation/replacement state handling only. It preserves page requests, pagination offsets, merge/dedupe rules, poster selection and Library state projection. No speed or network-call reduction is claimed.

### Evidence and verification boundary

- The original code path was traced from `load` / `loadMore` through `fetchPage` and `observeInternalLibrary`: cancellation was captured by `runCatching`, and publication was guarded by request equality rather than operation identity.
- Context7 was queried for the project's pinned AtomicFU API; the version catalog specifies 0.32.1. The available API reference confirms volatile `AtomicRef.value` access and atomic `getAndSet`, which are used for the active request/job slots. Context7's coroutines results were from 1.10.1 because the exact pinned 1.10.2 was not listed.
- Exa checked the official [Kotlin cancellation guidance](https://kotlinlang.org/docs/cancellation-and-timeouts.html) and kotlinx.coroutines' [CancellationException/runCatching issue](https://github.com/Kotlin/kotlinx.coroutines/issues/1814). Both describe cancellation as control flow that must not be swallowed; the issue also documents why blindly treating every CancellationException as proof of the current job's cancellation is not universally correct. This repository rethrows the caught exception and independently guards request ownership.
- Added the regression test before the production change. No test, Gradle task, build, CI, device run or benchmark was executed. The test and implementation were re-fetched and statically reviewed; compilation and runtime verification remain outstanding.

## Metadata detail loads — cancel and fence stale addon/server responses (Cycle 118, 20 November 2026)

`MetaDetailsRepository.load` launched addon-backed loads without retaining their jobs. Navigating to another title or calling `clear()` did not stop the previous operation. The normal addon path could then publish its result or fallback error without checking whether its request was still selected. The server-backed path did check `activeRequestKey`, but a sequence such as A → B → A could make an old A job pass the key check again. Enrichment also wrote the shared metadata cache from inside `withContext(Dispatchers.Default)`, even though the cache is an ordinary mutable map owned by the repository's Main-dispatcher workflow.

Changed:
- Centralized active metadata load creation in a lazy-start helper that cancels the previous job, installs the new job identity and request key, then starts the new job.
- Added exact job-identity plus request-key checks before addon/server success and failure publication, and after suspending provider resolution, fetch and enrichment operations.
- Made `clear()` cancel and detach the active job before resetting the request key and UI state, preventing late responses from repopulating a cleared detail screen.
- Applied the same ownership fence to cached-base enrichment and server-backed details, not only the primary addon request path.
- Moved the enriched-cache write out of the `Dispatchers.Default` enrichment function and back to the repository's Main-dispatcher continuation after ownership is rechecked. This avoids concurrent mutation of the ordinary metadata cache from the worker dispatcher.
- Added focused tests proving that the same metadata key cannot authorize a superseded job and that a cleared/changed request is rejected.

This preserves the existing provider priority, timeout values, fallback behavior, metadata cache keys, enrichment settings and detail UI models. It changes which request is allowed to publish; it does not claim faster metadata fetching.

### Evidence and verification boundary

- The source review traced the normal addon path, cached-enrichment path, server-details path, `publishLoadedMeta`, `enrichForMetaScreen` and `clear()`. The stale-publication gap and off-dispatcher cache mutation were visible in those call paths.
- Context7's kotlinx.coroutines 1.10.1 documentation was checked for cancellation propagation and retrieving the current `Job` from `currentCoroutineContext()`; exact 1.10.2 docs were not available in the resolved version set. The implementation uses the stable `Job` identity contract and lazy coroutine start.
- Exa checked official Kotlin cancellation guidance and the kotlinx.coroutines issue linked above. A cancelled request must not continue publishing state; ownership checks also cover non-cooperative or already-completed work that returns after a newer request takes ownership.
- Added the regression test before the production change. No test, Gradle task, build, CI, device run or benchmark was executed. The production source and test were re-fetched for static review; compilation and runtime verification remain outstanding.

## Addon manifest parsing — ignore malformed optional values without discarding valid resources

**Engineering cycle:** 119

Addon manifests are supplied by external providers. The parser previously used the throwing `jsonPrimitive` and `jsonObject` convenience accessors for optional values and nested resource/catalog entries. A manifest containing a valid required header plus one optional field with the wrong JSON shape (for example, an object-valued `description`, array-valued `logo`, or scalar entry in `catalogs`) could therefore fail the entire manifest parse. A malformed item in `resources` or `catalogs` could also prevent valid sibling entries from being retained.

Changed:
- Replaced optional primitive access with safe `as? JsonPrimitive` checks for strings, booleans, integers and string arrays.
- Validate resource and catalog entries as `JsonObject` before reading them; skip malformed entries and entries missing their required per-entry identifiers.
- Validate nested catalog extras before reading their names/options, preserving valid extras in their original order.
- Treat a wrong-shaped `behaviorHints` value as absent defaults.
- Keep the top-level manifest object and required manifest identity fields strict; this does not turn an invalid manifest into a valid one.

Added a focused common test containing malformed optional values and mixed valid/invalid resource, catalog and extra entries. It asserts that valid sibling metadata remains available.

### Evidence and verification boundary

- The original failure path was traced through `resources`, `catalogs`, `behaviorHints`, `optionalString`, `stringList`, `boolean` and `int`: these used throwing subtype accessors on provider-controlled JSON.
- Context7 resolved kotlinx.serialization but did not offer the project's pinned 1.10.0 in its version list; the available official library documentation was queried at its listed version. It confirms that `jsonPrimitive` and `jsonObject` throw `IllegalArgumentException` when the element has a different JSON subtype.
- Exa independently returned the official [Kotlin JSON elements guide](https://kotlinlang.org/docs/serialization-json-elements.html) and the [kotlinx.serialization JsonElement implementation](https://github.com/Kotlin/kotlinx.serialization/blob/master/formats/json/commonMain/src/kotlinx/serialization/json/JsonElement.kt), confirming the subtype-cast behavior and the three distinct JSON element types.
- The new regression test and changed parser were re-fetched from `main-build-testing` and statically inspected. No test, Gradle task, build, CI, device run or benchmark was executed; compilation and runtime behavior remain unverified.

## Metadata response parsing — isolate malformed optional fields from valid detail data

**Engineering cycle:** 120

The metadata parser accepts responses from addon/provider services. Its scalar helpers and array readers used the throwing `jsonPrimitive` accessor for optional fields. A single wrong-shaped value in genres, director/cast lists, video fields, trailer fields or embedded stream metadata could throw while parsing the whole title, even when the required `id`, `type` and `name` and other sibling fields were valid. This is separate from stream-list parsing: it affects the detail metadata response and its nested credits, episodes, trailers and embedded streams.

Changed:
- Make optional string, integer, long and boolean reads use safe `JsonPrimitive` subtype checks.
- Filter malformed elements from string arrays and CSV/list-backed credit fields instead of throwing.
- Keep required title identity fields required; malformed required values still fail the metadata response rather than silently fabricating identity.
- Preserve valid links, people, videos, trailers, season posters and embedded streams when malformed optional siblings are present.

Added a focused common test with mixed valid and malformed metadata values across genres, credits, episode fields, trailers, behavior hints, season posters and embedded streams. It asserts that valid title and episode data survives.

### Evidence and verification boundary

- The parser was traced from `parse` through its scalar helpers, `stringList`, `stringListOrCsv`, people/credit parsing, video/trailer parsing and embedded stream parsing. The unsafe subtype reads were all optional-value paths; required identity remains strict.
- The same official Context7 and Exa JSON DOM references documented above apply: the convenience accessors throw for mismatched JSON subtypes, while explicit subtype checks let callers decide how to handle unknown structures.
- The changed parser and regression test were re-fetched from `main-build-testing`; static inspection confirmed the parser no longer calls `jsonPrimitive` and the regression case is present. No test, Gradle task, build, CI, device run or benchmark was executed; compilation and runtime behavior remain unverified.

## Home poster shelves — reuse duplicate-safe lazy keys across recompositions

**Engineering cycle:** 121

The shared `NuvioShelfSection` previously called `entries.withDuplicateSafeLazyKeys(key)` inside the `LazyRow` content builder. That helper performs a full key-counting pass, allocates a count map and occurrence map, then allocates a second list of wrapper entries. The work was repeated whenever the shelf content recomposed, even when its entries and key function were unchanged. This shared component is used by the Home catalog and continue-watching shelves, so the cost was in a high-visibility scrolling path.

Changed:
- Move duplicate-safe key derivation into a `remember(entries, key)` calculation outside the `LazyRow` DSL.
- Rebuild the key list only when the row entries or key function changes.
- Preserve the existing key values, duplicate suffix order, lazy item identity and item rendering paths. Unkeyed rows still use the original entries directly.

The existing duplicate-key regression test continues to cover deterministic key values and occurrence suffixes. No UI structure, prefetch window or visual behaviour was changed.

### Evidence and verification boundary

- The full path was inspected in `ShelfComponents.kt` and `DuplicateSafeLazyKeys.kt`: the key helper did two collection passes and created multiple temporary collections before the lazy items were declared.
- Context7 queried the official AndroidX Compose Runtime `remember(key1, key2)` reference. It confirms the cached calculation is reused while both keys remain equal and recalculated when either changes. Context7's Compose Multiplatform version list did not include the project's pinned 1.13.0-beta01, so the API reference was checked against the official AndroidX Compose Runtime reference rather than claiming an exact-version lookup.
- Exa returned the official [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices), which explicitly demonstrates moving expensive list transformations out of lazy-list content and caching them with `remember`.
- The modified shelf source and existing key regression test were re-fetched from `main-build-testing` for static review. No test, Gradle task, build, CI, device run or benchmark was executed; runtime scroll smoothness and compilation remain unverified.

## Home catalog publication — cache custom-poster transformations for unchanged sections

**Engineering cycle:** 122

Home publishes progressively as catalog requests complete. In each publication, `publishCurrentState` previously reapplied the custom-poster URL pattern to every already-loaded section. `reapplyCustomPosterUrls` maps the full item list and reconstructs poster metadata even when the source section and active pattern have not changed. The existing per-publication map only avoided duplicate work between the visible rows and hero calculation in that single publication; it did not reuse the transformation for the next publication.

Changed:
- Added a bounded-by-active-catalog-key cache of transformed sections, keyed by catalog cache key, source section identity and active poster pattern.
- Reuse the transformed section across subsequent progressive publications while both the immutable source section and pattern remain unchanged.
- Recompute when a catalog refresh replaces the source section or when custom-poster settings change.
- Prune entries when the active catalog definition set changes and clear the cache when Home state is cleared.
- Protect cache reads, writes and pruning with AtomicFU's multiplatform `SynchronizedObject` lock because Home publication and settings refresh paths can run on different dispatchers.

Added a regression test asserting that an unchanged section/pattern reuses the same transformed object, while a changed pattern, replaced source section or pruned cache entry triggers transformation again.

### Evidence and verification boundary

- The repeated work was traced in `HomeRepository.publishCurrentState`: each call creates a fresh local overlay map, then invokes `reapplyCustomPosterUrls` for every section reached by both row publication and hero candidate selection. The catalog completion loop can publish multiple times as different sections arrive.
- The cache uses source object identity rather than structural section equality. A new fetch creates a new immutable `HomeCatalogSection`, and a pattern change is an explicit cache key; this avoids scanning full poster lists just to decide whether the cached transformation is reusable.
- Context7 queried the project's AtomicFU lock API. The available official reference documents `SynchronizedObject` and `synchronized` across JVM and Native; the project pins AtomicFU 0.32.1, but Context7 did not provide a version-specific 0.32.1 page. The cache only holds its lock for map access; poster transformation happens outside the critical section.
- Exa's official Compose performance guidance was also used for Cycle 121. No performance percentages or frame-time improvements are claimed here: this change removes repeated full-section poster transformations from unchanged progressive publications, but the actual user-visible effect still requires a permitted device benchmark.
- The new cache regression test and modified production files were re-fetched from `main-build-testing` and statically reviewed. No test, Gradle task, build, CI, device run or benchmark was executed; compilation and runtime behaviour remain unverified.

## Catalogue poster grid — reuse duplicate-safe keys by source identity (Cycle 123, 20 November 2026)

The main `CatalogScreen` previously called `withDuplicateSafeLazyKeys` from inside the `LazyVerticalGrid` content builder. That helper performs a key-counting traversal, allocates a key-count map and occurrence map, then maps the full catalogue into wrapper entries. Recomposition of the grid could repeat this full-list work even when the repository had not replaced the item list.

Changed:
- Added a single-entry `DuplicateSafeLazyKeysCache` that keys its cached projection by source-list reference identity (`===`), avoiding a structural equality scan on every composition.
- The cache is remembered for the lifetime of the catalogue screen and rebuilds only when `CatalogUiState.items` is replaced.
- Kept the existing duplicate suffix algorithm and encounter order unchanged. The existing helper remains available to other callers.

Regression coverage now checks stable duplicate suffixes, projection identity reuse for the same list instance, and rebuilding for a different list instance with equal contents.

### Evidence and verification boundary

- The live `CatalogScreen` call site and `DuplicateSafeLazyKeys.kt` were inspected. The former performed the complete key projection inside the lazy grid DSL; the latter does two key-selector passes and creates maps plus a mapped wrapper list.
- Exa returned Android Developers' official [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices), which recommends moving expensive collection transformations out of lazy content and caching the result. The cache uses reference identity rather than a large collection as a `remember` key, avoiding structural equality checks.
- Context7 resolved the project's Compose Multiplatform documentation, but its indexed release list did not include the pinned 1.13.0-beta01 for a version-specific `remember` API page. The implementation retains the existing `remember` API already used in this source file and adds no Compose API or dependency.
- This is a source-level reduction in repeated catalogue-wide work; no frame-time or scroll-latency percentage is claimed.
- Regression tests and production sources were re-fetched from `main-build-testing` for static review. No Kotlin test, Gradle task, build, CI, device run or Macrobenchmark was executed. The test-first RED/GREEN run therefore remains outstanding.

## Local Library snapshots — reuse item-list copies for metadata-only revisions (Cycle 124, 20 November 2026)

`LibraryLocalState.snapshotLocked()` previously copied `itemsById.values` into a new list on every snapshot, including transitions that changed only revision metadata or pending-push state. The state already tracks `contentRevision` separately from the general `revision`, so the code can distinguish content mutations from metadata-only transitions without comparing the full item collection.

Changed:
- Retain one immutable list snapshot for the current `contentRevision`.
- Reuse that list when a new snapshot is requested after a metadata-only revision.
- Rebuild it lazily after a content revision changes. Profile load/reset paths already advance content revision, so a profile switch cannot reuse the previous profile's item list.

Regression coverage asserts that push-completion metadata changes reuse the exact item-list instance, while a subsequent content mutation produces a new list instance.

### Evidence and verification boundary

- The existing `LibraryLocalSnapshot.contentRevision` and all content mutation paths in `LibraryLocalState` were inspected. Snapshot creation was copying the item map values regardless of whether the content revision had changed.
- The cache is a single current-revision list, guarded by the same existing state lock as the mutable map; it adds no new lock or dependency and has bounded retention.
- No ordering, membership, profile, pending-push, or persistence semantics are changed.
- The focused regression test and implementation were re-fetched for static review. No Kotlin test, Gradle task, build, CI, device run or benchmark was executed; the RED/GREEN run remains outstanding.

## Local Library publication — cache sorting and section grouping by content revision (Cycle 125, 20 November 2026)

The local-library branch of `LibraryRepository.publish()` sorted all saved items by descending save time, grouped them by media type, created section objects and sorted those sections on every publication. `publish()` can run for provider-connection changes, source-setting updates and other repository events even when the local saved-content collection is unchanged. The existing profile token and `contentRevision` provide a precise cache key for this projection.

Changed:
- Added a one-entry `LibraryContentProjectionCache`, keyed by both `LibraryProfileToken` and `contentRevision`.
- Reuse the sorted item list and grouped/sorted sections when a publication changes only non-content state.
- Recompute when saved content changes or the active profile generation changes.
- Keep sorting/grouping outside the cache lock; the lock protects only cache reads and publication of the completed projection.
- Provider-backed Library publication remains untouched.

Regression coverage checks that a metadata-only revision reuses the same projection, retains descending save-time order, and rebuilds after a content mutation.

### Evidence and verification boundary

- The live `LibraryRepository.publish()` path was inspected: sorting, `groupBy`, section mapping and section sorting were performed unconditionally for local mode. The repository already exposes the profile token and distinct content revision needed for safe invalidation.
- Context7 resolved the AtomicFU documentation and verified the existing `SynchronizedObject` / `synchronized` API for multiplatform cache protection. The project pins AtomicFU 0.32.1; Context7 returned the official API reference but not a version-specific 0.32.1 page. No new dependency or API was introduced.
- The cache retains only one projection and includes the profile generation in its key. It does not cache provider-backed projections or alter the visible sort/group order.
- Regression tests and source files were re-fetched from `main-build-testing` for static review. No Kotlin test, Gradle task, build, CI, device run or Macrobenchmark was executed; the RED/GREEN run remains outstanding.

## Search progressive results — skip full-list projection for failed sources (Cycle 126, 20 November 2026)

The search repository runs add-on and server catalog requests concurrently and stores each completion in an indexed result array so visible sections retain configured source order. Before this change, every completion — including a failed request with no section — called `orderedSections()`, scanning the full result array and allocating a new list. A failed source cannot change the visible section list, so this work was unnecessary.

Changed:
- Added `SearchProgressAccumulator` to own indexed completions and preserve configured source order.
- `record()` stores every success or failure, but only builds the progressive section snapshot when a result contains a section.
- Final error aggregation still sees every completed result; final section order and loading/error behavior remain unchanged.
- Added regression coverage for failed-only completions, out-of-order successful arrivals, stable source ordering, and errors arriving after visible sections.

### Evidence and verification boundary

- The live `SearchRepository.search()` loop was inspected. It previously ran `results.orderedSections()` for every channel message, even when `result.section == null`.
- `MutableStateFlow` uses equality-based conflation, so identical `SearchUiState` assignments do not notify collectors. This change is therefore documented as eliminating redundant full-array scans and list allocations on failures, not as eliminating recompositions. Context7 and the official kotlinx.coroutines API confirm the equality-based conflation behavior.
- The request fan-out, cancellation generation, error aggregation, and configured source ordering are unchanged.
- The focused regression tests and implementation were re-fetched for static review. No test, Gradle task, build, CI, device run or benchmark was executed.

## Player embedded streams — avoid rescanning episode metadata for root stream lookup (Cycle 127, 20 November 2026)

When playback requests streams using the parent metadata ID, `findEmbeddedStreamsForMeta()` first scanned all videos to detect multiple stream-bearing episodes, then performed a second full scan with `flatMap` to collect their streams. For series with embedded streams across many episodes, that repeated traversal and intermediate list allocation occurs on the playback stream-resolution path.

Changed:
- While performing the existing lookup pass, collect the combined stream list only when the lookup is for the root metadata ID and a second stream-bearing video is encountered.
- Append later episode streams in their existing video order.
- Return the accumulated list for the root fallback rather than traversing all videos again.
- Exact video-ID, season/episode coordinate, and prefix lookup precedence is unchanged. Non-root lookups do not allocate the aggregate list.

Regression coverage checks root aggregation order and verifies exact episode lookup still returns the original episode stream-list instance.

### Evidence and verification boundary

- The live `findEmbeddedStreamsForMeta()` implementation was inspected. Its root fallback explicitly performed `meta.videos.flatMap { it.streams }` after already traversing `meta.videos` to determine whether more than one episode contained streams.
- The new accumulation starts only after the second stream-bearing video and only for a root-ID lookup, avoiding an extra collection allocation on direct episode lookups and single-video cases.
- No stream selection policy, episode matching priority, or ordering was changed.
- The focused regression tests and implementation were re-fetched for static review. No test, Gradle task, build, CI, device run or benchmark was executed.



## Smart Streams Best Match and Android startup diagnostics review (21 November 2026)

Source changes are isolated on `review/playback-startup-best-match-20261121`; not merged into `main-build-testing`.

- Smart Streams Best Match candidates now apply the active Smart Streams filters to the full source state and the selected provider filter, while remaining independent of the separate free-text stream-list search. Ranking weights, low-quality fallback and stable order tie-break remain unchanged.
- Added common regression-test source for resolution filtering, Instant Only/unresolved torrent exclusion, provider selection and free-text search separation.
- Android Media3 1.8.0 diagnostics now record the first manifest and media load starts/completions per player listener, including elapsed realtime, load duration, bytes and track type. Started URLs use existing redaction; completion logs are bounded to one per type, and negative elapsed events are ignored. Review found that these guards are per listener lifetime, not per prepare, and `EventTime` event identity is not compared with current player identity; the logged attempt is therefore not authoritative when callbacks are delayed or pre-buffering another item. Do not use this as per-attempt timing until that correlation is fixed and verified.
- No playback buffer, HLS adaptation, source fallback, DNS, or stream-link cache policy was changed.

**Verification:** static remote source re-fetch and review only. No RED/GREEN test run, Kotlin compilation, Android build, CI, physical-device playback, Macrobenchmark or measured latency comparison was performed. No startup speedup is claimed. See [player startup optimization and diagnostics](player-stream-startup-optimization-2026-11-19.md) and the review design/plan in `docs/superpowers/specs` and `docs/superpowers/plans`.


## Android release profile audit — verify flavor-specific Baseline Profile artifacts before tuning startup (Cycle 128, 21 November 2026)

A separate cold-start audit checked the Android Baseline Profile wiring rather than changing playback or Compose behavior.

Findings:
- `androidApp/src/main/baseline-prof.txt` exists but is zero bytes. This alone does **not** prove that release variants ship without a profile: `androidApp/build.gradle.kts` sets `baselineProfile.mergeIntoMain = false`, so the generic main source-set file is not the expected place to judge the generated release profiles.
- The app applies the Baseline Profile plugin, depends on `:baselineProfile`, and includes ProfileInstaller. The generator module targets `:androidApp` and declares matching `full` and `playstore` distribution flavors.
- Profile persistence is configured with `saveInSrc = true`, while `automaticGenerationDuringBuild = false`. A normal release build therefore must not be assumed to refresh the profile; profile generation is an explicit workflow.
- The app has separate optimized `benchmarkRelease` and non-minified `nonMinifiedRelease` build types with the same `.benchmark` application ID suffix, consistent with separating profile capture from optimized release packaging.
- The Android manifest declares `com.nuvio.app.launcher.AppIconDefault` as the default launcher activity. Other launcher aliases are also declared for icon variants; the profile journey must launch through the real app entry point rather than accidentally profile an internal activity.

Decision:
- Do not hand-author ART profile rules or copy guessed rules into `src/main`. That would be speculative and could optimize the wrong classes or omit real critical journeys.
- Before calling the profile complete, inspect the actual generator test source and the generated output for each distribution flavor, confirm that startup capture is marked for startup-profile inclusion, and inspect the final release APK/AAB for a non-empty compiled profile at the documented packaging location.
- Include only measured, representative critical user journeys in the general Baseline Profile; keep startup-only interactions distinct from later navigation/playback journeys so non-startup code does not pollute the Startup Profile.
- Compare cold TTID/TTFD on the same physical device and equivalent signed-in state with profile compilation disabled versus required Baseline Profile compilation. Keep profile changes only when repeated measurements show a real improvement without regressions.

Evidence and limits:
- Re-fetched the live `androidApp/build.gradle.kts`, `baselineProfile/build.gradle.kts`, `gradle/libs.versions.toml`, main Android manifest and profile source file from this review branch. The repository pins AGP 9.4.0, AndroidX Benchmark Macro 1.6.0-alpha02 and ProfileInstaller 1.4.1.
- Context7 and Android Developers guidance confirm that generated profile output is variant-sensitive, that `includeInStartupProfile` should be reserved for startup journeys, and that the compiled profile must be checked in the packaged release artifact. A zero-byte `src/main/baseline-prof.txt` is not enough to infer whether flavor-specific generated profiles are present.
- The GitHub file connector could not resolve the generator source file's package path or enumerate the module directory. Therefore generator coverage and the generated flavor-specific profile contents remain **unverified**, not declared missing.
- No source behavior, profile rules, Gradle configuration or dependencies were changed. No Gradle task, profile generation, APK/AAB build, CI, physical-device benchmark or performance measurement was run.


## Android first-frame critical path — trace-gated initialization audit (Cycle 129, 21 November 2026)

A second new-area review traced the Android launcher Activity before Compose content is installed.

Confirmed call-path facts:
- `MainActivity.onCreate()` performs several groups of initialization synchronously on the main thread before `showContent()` calls `setContent`: `preSuper`, `identityNetwork`, `coreStorage`, `profileMetadata`, and `integrationsDownloads`.
- The existing `traceStartupSection` helper wraps each group with Android `Trace.beginSection/endSection`, so the code already exposes coarse Perfetto slices for those blocks.
- `SentryInitializer.start(application)` is inside the `preSuper` trace. Its first `SentrySettingsRepository.ensureLoaded()` and `applyEnabled(...)` calls run synchronously on the caller. If Sentry is enabled and a DSN is configured, `applyEnabled` calls `SentryAndroid.init` before the Activity proceeds to `super.onCreate()`.
- The profile/home/watch-progress critical preload is a separate coroutine phase. Profile payload decoding explicitly switches to IO; watched/watch-progress and the independent Home snapshot preload run through the existing IO dispatcher. The post-frame warmup is deliberately deferred until after a Compose frame.

Decision:
- Do not move Sentry initialization or storage initialization to another dispatcher based only on source inspection. Android lifecycle ordering, SDK threading guarantees, early crash coverage and synchronous preference access need to be respected.
- The next evidence-producing step is a Perfetto/System Trace capture on the physical device, comparing the five named Activity initialization slices and the first Compose frame. Repeat with Sentry enabled and disabled in otherwise identical app state to determine whether `SentryAndroid.init` is a material contributor rather than assuming it is.
- If one section dominates, inspect its individual initializer call sites and move only work proven safe to defer or execute off-main. Preserve storage readiness before dependent repositories and preserve startup error-reporting requirements.

Verification boundary:
- Re-fetched `MainActivity.kt`, `NuvioApplication.kt`, `StartupPreloader.kt`, `ProfileRepository.kt`, and the Sentry initializer from the live review branch. This is a source call-graph audit, not a performance measurement.
- No startup code, lifecycle ordering, Sentry configuration, storage behavior, or preload scheduling was changed. No tests, Gradle tasks, build, CI, Perfetto capture or device benchmark was run. No startup improvement is claimed.


## Theme settings first-composition storage audit (Cycle 130, 21 November 2026)

The Android startup path initializes `ThemeSettingsStorage` in the already-traced `Nuvio.Startup.preSuper` section. Later, `MainActivity.showContent()` calls `setContent`, and the root `AppEnvironment` composable executes `ThemeSettingsRepository.ensureLoaded()` inside a Compose `remember` calculation before collecting the selected theme. This is a distinct first-composition path from the five Activity initialization groups audited in Cycle 129.

The source call chain is concrete:
- `ensureLoaded()` calls `loadFromDisk()` synchronously on the caller when `hasLoaded` is false.
- `loadFromDisk()` reads the theme and multiple appearance preferences, decodes the stored Liquid Glass settings, and applies the effective theme before returning.
- On Android, `ThemeSettingsStorage.loadCustomSettingsBackgroundImageUri()` also clears a legacy preference and checks `File.isFile` and `File.length()`; `loadCustomLibraryCloudBackgroundImageUri()` performs another file existence/length check. These calls occur inside that same synchronous load.
- The caller is the initial Compose composition path, not a coroutine or an explicit IO dispatcher. The Android storage uses SharedPreferences and local files; the two background-image metadata checks are filesystem operations.

**Decision:** do not move this wholesale to IO or convert it to DataStore without a migration design. Theme values, Liquid Glass overrides, profile-scoped preferences, language application, legacy migration writes and native tab bridge publication have ordering/first-frame implications. A safe next change needs an explicit loading/readiness contract that prevents an incorrect default-theme flash, plus tests for profile switching, migration, missing/corrupt settings and custom background image publication. Measure this path with a dedicated trace section around `ensureLoaded` and compare cold/warm TTID/TTFD before choosing the scope. This audit identifies synchronous work; it does not establish that it is a significant delay or claim a startup speedup.

### Evidence and verification boundary

- Re-fetched `MainActivity.kt`, `App.kt`, `ThemeSettingsRepository.kt`, the common storage contract and Android storage implementation from the live review branch.
- Context7 checked the exact pinned Coil 3.6.3 singleton-loader guidance separately; it confirms the existing Android `SingletonImageLoader.Factory` and Compose Multiplatform root factory are supported patterns. No image-loader change is indicated by this audit.
- Context7's Android DataStore documentation confirms its asynchronous Flow-based read model, but adopting it would be a storage migration, not a safe mechanical dispatcher change.
- No production code or tests were changed. No test, Gradle task, build, CI, Perfetto capture or device benchmark was run.


## Coil singleton image-loader lifecycle audit (Cycle 131, 21 November 2026)

Reviewed the two platform configuration paths rather than changing image loading speculatively:
- Android `NuvioApplication` implements `SingletonImageLoader.Factory`; the loader is configured before Activity composition and includes the app's custom artwork interceptors, shared OkHttp-backed Ktor fetcher, cache-control strategy, request de-duplication, SVG and API-level GIF decoder.
- The common `AppEnvironment` installs `setSingletonImageLoaderFactory` only when `platformProvidesImageLoader` is false. This is the Compose Multiplatform entrypoint path and does not unconditionally replace the Android Application factory.
- Both paths enable memory/disk caching and concurrent request de-duplication. The Android factory's shared HTTP engine configuration is intentional and preserves the app's shared network behavior; no second Android loader creation path was established from the inspected source.

Context7 was queried against the exact catalog-pinned Coil 3.6.3 documentation. It explicitly supports `SingletonImageLoader.Factory` on Android and `setSingletonImageLoaderFactory` near the root composable for Compose Multiplatform. The current platform split is consistent with those documented patterns. No production change is justified; replacing this with another loader or altering cache policy without request-level evidence risks losing custom artwork handling, shared networking, or cache reuse.

**Verification boundary:** `App.kt`, `NuvioApplication.kt` and `gradle/libs.versions.toml` were fetched from the review branch. No runtime request trace, image-cache benchmark, test, build, CI or device run was performed. No image-loading speedup is claimed.


## Android DoH dual-stack resolution wait audit (Cycle 132, 21 November 2026)

Inspected the custom Android `NuvioDns` path and its actual `DohClient` implementation.

Confirmed behavior:
- For non-system DNS providers, `NuvioDns.lookup()` calls `DohClient.resolve()` synchronously from OkHttp's `Dns.lookup` contract.
- `resolveDohAnswers()` starts the AAAA query asynchronously, executes the A query synchronously, then calls an un-timed `CountDownLatch.await()` before returning the combined addresses.
- The DoH OkHttp client has connect=5s, read=5s and call=8s timeouts. Therefore a slow or unresponsive AAAA response can hold a successful A lookup until that independent call finishes or times out; the caller does not receive the usable IPv4 answer immediately.
- If the A query fails, the outstanding AAAA request is cancelled. If AAAA fails, the failure is treated as optional and IPv4 is returned. Successful address responses are cached only after the combined resolution returns; late AAAA completion is not retained after the resolver has returned.
- The outer resolver falls back to system DNS on DoH `IOException`, but the AAAA wait itself is not separately bounded by a resolution-delay policy.

External standards check: RFC 8305 §3 says dual-stack clients should issue A/AAAA queries close together and should not wait for both responses before attempting connection establishment; after an A response it recommends a 50ms resolution delay for a pending AAAA response. This is relevant evidence that waiting for a slow optional family can delay usable connectivity. However, this app currently implements OkHttp's synchronous `Dns.lookup(hostname): List<InetAddress>` contract, which returns one completed address list; adopting the full asynchronous RFC behavior would require understanding OkHttp's address/connection-attempt lifecycle or introducing a carefully designed bounded wait and late-result cache.

**Decision:** no arbitrary 50ms cutoff or dropped-IPv6 change was made. That would change address ordering and could discard IPv6 for the current connection; a correct implementation must preserve late AAAA results for later requests and handle provider changes, cancellation, cache TTL and duplicate completion. Next step is a regression-tested resolver design plus device/network fault-injection measurements (fast A/slow AAAA, slow A/fast AAAA, both fail, provider switch during lookup). No code or tests changed; no DNS benchmark, device test, build or CI was run. No connection-speed improvement is claimed.

### Evidence

- Re-fetched `NuvioDns.kt`, `DnsPlatform.android.kt`, `SharedOkHttpClient.kt` and the live branch's startup/network sources.
- Exa reviewed RFC 8305 and OkHttp DNS API/source guidance. Context7 was used for Android platform API documentation in the preceding audit; the RFC is the relevant normative source for dual-stack behavior.


## Startup preloader profile-cache dispatch audit (Cycle 133, 21 November 2026)

Inspected the critical preload coordinator and its profile-cache dependency.

- `StartupPreloader` owns a `Dispatchers.Main` scope and starts the critical phase from that scope.
- `ProfileRepository.loadCachedProfiles()` explicitly switches payload decoding to `Dispatchers.IO`; `applyStoredPayload()` also switches PIN-cache synchronization to IO. The profile payload read and PIN-cache synchronization are therefore not synchronous main-thread work, despite the Main-scope call site.
- After applying the cached profile, `loadCachedProfiles()` calls `ThemeSettingsRepository.onProfileChanged()` on the caller dispatcher. That method calls the synchronous theme settings loader identified in Cycle 130. It is the same shared settings path, not a separate profile-cache optimization target.
- `runStartupPreloadWork()` schedules independent reads and ordered watched/watch-progress reads on the supplied IO dispatcher. Existing tests cover the helper's overlap behavior.

**Decision:** retain the existing IO dispatch for profile payload decoding and PIN-cache sync. Trace the shared theme settings loader at both entry points before changing its readiness contract. This avoids duplicate work and prevents misreporting the profile payload read as main-thread disk I/O.

**Verification:** re-fetched `StartupPreloader.kt`, `ProfileRepository.kt`, `ProfileStorage.android.kt` and `StartupPreloaderTest.kt`. Tests were inspected but not run. No code or tests changed; no Gradle task, build, CI, device trace or benchmark was run.


## Home snapshot preload dispatcher audit — correction (Cycle 134, 21 November 2026)

The initial Cycle 134 conclusion that Home snapshot file I/O and JSON decoding ran on Main was incorrect. Re-inspection of the full call chain shows that `StartupPreloader.preloadCriticalPhase()` invokes `runStartupPreloadWork(dispatcher = Dispatchers.IO)`; its independent Home snapshot callback is launched with `async(dispatcher)`. Therefore `HomeRepository.preloadSnapshot()`, `HomeSnapshotStore.load()`, Android `AppFileCache.read()`, JSON decoding and model mapping execute on the supplied IO dispatcher in this startup path. The fact that the owning `StartupPreloader.scope` uses Main does not override the explicit dispatcher used for this independent task.

**Decision:** withdraw the proposed dispatcher change. It would duplicate existing IO dispatch and add no demonstrated benefit. The earlier Cycle 134 main-thread finding must not be used as a performance candidate or as justification for a production change. The current `StartupPreloaderTest` verifies that independent startup reads overlap the ordered critical reads, but it does not assert the actual Home snapshot thread; no new test is required to justify a dispatcher fix because no such fix is needed on this path.

This correction was established by tracing `StartupPreloader.preloadCriticalPhase()` → `runStartupPreloadWork(Dispatchers.IO)` → `async(dispatcher)` → `HomeRepository.preloadSnapshot()`. No production code changed. No tests, build, CI, StrictMode capture, Perfetto trace or device benchmark was run.


## Media3 startup diagnostic ownership audit (Cycle 135, 21 November 2026)

Re-opened the isolated playback-hardening branch's actual `PlayerEngine.android.kt` implementation and checked the callback contract against the repository-pinned Media3 version.

**Confirmed root cause**
- The listener currently computes elapsed time from the mutable `PlaybackDiagnostics.prepareStartedAtMs`, then labels the callback with the mutable `PlaybackDiagnostics.attempt` at callback-delivery time.
- It does not inspect `AnalyticsListener.EventTime.timeline`, `windowIndex`, `mediaPeriodId`, or their `current*` counterparts. Resetting the one-time flags in `beginAttempt()` therefore does not establish which prepare attempt owns a load event.
- Android's official Analytics documentation explicitly says the event's timeline/window/mediaPeriod identify the item to which the event belongs, while `currentTimeline/currentWindowIndex/currentMediaPeriodId` describe the item current at callback time; those can differ when the event belongs to a pre-buffered next item.
- The official API also defines `EventTime.timeline` as the most recent timeline containing the event position, and `windowIndex` as a prospective index when the timeline is not yet known and empty. A null `mediaPeriodId` is valid for events not associated with a specific period.

**Important initial-HLS limitation**
- An initial manifest event can legitimately arrive before the timeline is populated. In that case, event identity may be only a prospective window index, with no event media-period ID. Comparing the event item to the current item is not a proof of ownership in that state.
- Consequently, a simple equality check against `currentMediaPeriodId`, or a timestamp check against the latest prepare start, is insufficient. Blindly rejecting all empty-timeline events would also discard legitimate initial manifest observations.
- The current logs must remain provisional; they must not be used to compare per-attempt manifest/media startup timings or to justify buffer-policy changes.

**Decision**
- No speculative callback filter or media-item-ID rewrite was added. A safe production fix requires a source-level ownership design that can associate each load event with the exact MediaSource/prepare generation, including the initial manifest before a timeline exists. The next implementation investigation must trace how the app constructs and wraps each MediaSource (including merged audio/subtitles and the HLS compatibility wrapper), then define testable ownership semantics for start, completion, retry, cancellation, prepare replacement and player rebuild.
- Preserve the existing diagnostics and their URL redaction until that ownership contract is implemented. Do not describe this as fixed.

**Evidence and verification boundary**
- Re-fetched the branch's `PlayerEngine.android.kt`, `PlaybackDiagnosticsTest.kt` and `gradle/libs.versions.toml`; the catalog pins Media3 1.8.0.
- Context7 resolved AndroidX Media3 but did not expose an exact 1.8.0 documentation version; its nearest indexed version was 1.8.1. No 1.8.1-only API was assumed.
- Exa subsequently fetched the exact AndroidX Media3 1.8.0 tagged `AnalyticsListener.java`, `MediaSourceEventListener.java`, `WrappingMediaSource.java` and `MergingMediaSource.java` sources. Context7's indexed 1.8.1 release notes were used only for the historical 1.5.0 merged-source event change, not to assume a 1.8.1-only API.
- The existing host tests cover counter reset/limits only; they do not exercise event identity, empty timelines, previous-item events or prepare replacement.
- No production code or tests changed. No Gradle test task, build, CI, benchmark, device run or playback experiment was started.


## HLS parameter-set inspection critical-path audit (Cycle 136, 21 November 2026)

Inspected `HlsParameterSetInitDataSource.open()`, `HlsParameterSetInit.withParameterSetInit()` and `HlsParameterSetInitTest` as a new playback-startup path, separate from the Media3 callback race.

**Confirmed call sequence**
- For a position-zero HLS playlist request, `open()` opens the upstream source and fully reads the playlist before invoking the optional SPS/PPS inspection.
- Only finished clear media playlists with at least two segments and without an existing map, byte ranges, discontinuities or encryption qualify for inspection.
- The inspection runs inside `resultWithin(CHECK_TIMEOUT_MS = 3_000)`. That helper starts a daemon thread but synchronously waits on `FutureTask.get(3 seconds)` before returning the original or rewritten playlist.
- The inspection fetches the second segment head first. Healthy streams stop after that one fetch; streams whose second segment lacks SPS/PPS then fetch the first segment head and may construct an init segment.

**Concrete latency implication**
- The 3-second timeout bounds only the optional segment-inspection wait; it does not bound the preceding playlist `upstream.open()` or `readAll()`.
- A slow segment host can therefore hold the playlist `open()` call for up to the full 3-second inspection timeout even when the stream is healthy and will ultimately be served unchanged. The nearby source comment says a slow segment host must not hold up startup, but the synchronous wait still permits this bounded delay.
- On timeout, `FutureTask.cancel(true)` interrupts the inspection worker. The existing test proves the helper returns within a broad bound for an interruptible `Thread.sleep`, but it does not prove every network `DataSource` promptly honors interruption or closes its socket. A lingering worker is a risk to test, not a confirmed leak.

**Decision**
- Do not lower the 3-second cap arbitrarily: on affected streams the inspection is the mechanism that creates the init segment needed to avoid the observed mid-stream H.264 no-frame stall. Do not move it asynchronously after returning the playlist, because the playlist must contain `EXT-X-MAP` before ExoPlayer parses it.
- The next safe improvement needs a testable policy that distinguishes the optional healthy-stream check from the required repair path, plus a controllable DataSource test for slow open/read, timeout, interruption, close and fallback-to-original-playlist behavior. Measure the impact on first playlist readiness and confirm the SPS/PPS workaround still activates on affected VOD before changing the timeout or execution strategy.

**Verification boundary**
- Re-fetched `HlsParameterSetInitDataSource.kt`, `HlsParameterSetInit.kt` and `HlsParameterSetInitTest.kt` from the review branch.
- Existing tests cover parser correctness, playlist eligibility, extractor behavior and the generic timeout helper; they do not cover the end-to-end DataSource `open()` latency or non-interruptible network operations.
- No source behavior or tests changed. No test task, build, CI, benchmark, network fault injection or physical-device playback was run.

### Media3 merged-source diagnostic attribution (Cycle 137, 21 November 2026)

Reconciled the exact AndroidX Media3 1.8.0 sources with the app's `setPlaybackMediaItem()` construction path.

- When `sourceAudioUrl` is present, the app builds separate video and audio `MediaSource` instances and wraps them in `MergingMediaSource`, then in `AudioDelayMediaSource`.
- Media3 release notes document that since 1.5.0, `MergingMediaSource` reports load events from secondary sources too, including sideloaded subtitles; duplicate load events can therefore reach `AnalyticsListener` for one logical playback item. This behavior is present in the repository-pinned 1.8.0 line.
- The current diagnostics claim the first manifest/media event by data type only. They do not distinguish video-source events from separate audio or subtitle-source events. A first media event may therefore describe a secondary source rather than the video whose first-frame latency is the primary startup target.
- `MediaSourceEventListener` in the exact 1.8.0 source exposes source-local `windowIndex` and nullable `MediaPeriodId`; `WrappingMediaSource` provides a supported single-child wrapping abstraction. This makes source-local attribution a viable investigation direction, but it still needs to handle merged sources and pre-timeline manifest loads explicitly.

**Decision:** do not use the current first-media log as a video-startup metric for dual-source playback. Future diagnostics should separate video/audio/subtitle source roles and include the prepare boundary. Keep the existing logs provisional until this is covered by source-level tests. No production code or tests changed; no test task, build, CI or device measurement was run.


## ExoPlayer prepare-entry-point coverage audit (Cycle 138, 21 November 2026)

Counted every `exoPlayer.prepare()` call and every `PlaybackDiagnostics.beginAttempt()` call in the branch's `PlayerEngine.android.kt`.

**Confirmed call-site coverage**
- There are six ExoPlayer `prepare()` call sites in this file.
- Only the initial `preparePlayback` path calls `playbackDiagnostics.beginAttempt()` before preparing ExoPlayer.
- The other five prepare paths are: Blu-ray title selection/recovery (`playDiscTitle`), the player-control manual retry, external subtitle attachment (`setSubtitleUri`), external subtitle removal (`clearExternalSubtitle`), and external subtitle removal plus built-in track selection (`clearExternalSubtitleAndSelect`).
- The separate mpv `LaunchedEffect` also calls `beginAttempt`, but that does not cover any of the six ExoPlayer paths.

**Impact**
- These paths can prepare/reprepare the same ExoPlayer while retaining the existing diagnostic attempt number and first-event guards. Logs for a retry, disc-title change or subtitle reload can therefore be omitted by already-consumed guards or attributed to the previous prepare start.
- The six paths are not semantically identical: manual retry re-prepares the current source; Blu-ray recovery changes the active title; subtitle operations replace the MediaItem at the current position. The eventual shared attempt API must make the boundary explicit at each caller rather than blindly incrementing from an AnalyticsListener callback.

**Decision**
- The next code change should centralize an explicit ExoPlayer diagnostic attempt-start helper and route all six call sites through it, while keeping player rebuild/listener lifecycle distinct from a new prepare attempt. Add host tests for each boundary and stale callback behavior before changing production code.
- No source code or tests changed in this audit. No test task, build, CI, benchmark or device run was started.

**Evidence**
- Re-fetched the complete `PlayerEngine.android.kt` and counted the six `exoPlayer.prepare()` occurrences and the ExoPlayer/MPV `beginAttempt()` occurrences directly from the remote branch.
- The implementation plan now tracks these missing boundaries and the separate audio/subtitle event attribution requirement.


## VOD disk-cache setting lifetime audit (Cycle 139, 21 November 2026)

Inspected the complete Android `PlaybackBuffering.android.kt` cache owner, the common `PlayerSettingsRepository` setters, the playback-buffer settings UI and the exact AndroidX Media3 1.8.0 cache implementation.

**Confirmed behavior**
- `PlaybackDiskCache` stores one process-wide `SimpleCache` in a nullable singleton field. `get(context, settings)` returns that same instance on every later call once initialized.
- The first call computes the `LeastRecentlyUsedCacheEvictor` maximum from either the configured `vodDiskCacheSizeMb` or one-fifth of current usable cache-directory space (clamped to 256 MiB–10 GiB), then permanently captures that value in the evictor.
- `PlayerSettingsRepository.setVodDiskCacheAutoSize()` and `setVodDiskCacheSizeMb()` publish and persist changes but do not notify, invalidate or reconfigure `PlaybackDiskCache`.
- The Media3 1.8.0 `LeastRecentlyUsedCacheEvictor` stores `maxBytes` in a `private final long` and exposes no setter. Its `evictCache()` uses that captured value when spans are added or a file is started.
- Therefore changing the cache size mode or configured size while the app process is alive does not change the active cache's eviction limit. The new setting is used only if the singleton is newly created in a later process lifetime. Auto-size likewise does not react to changing free disk space after first initialization.

**Impact**
- The settings UI can display a newly selected cache limit while the active cache continues enforcing its previous limit. This is a real settings/application mismatch, not a measured playback-startup regression.
- Simply constructing a second `SimpleCache` over the same directory is not a safe fix: Media3 1.8.0 documents that only one `SimpleCache` instance may own a directory at a time, and active `CacheDataSource` instances may still hold the existing cache.

**Safe implementation direction**
- Introduce a cache owner that can update its eviction policy without opening a second `SimpleCache` on the same directory. The most direct candidate is a small mutable LRU `CacheEvictor` implementing Media3 1.8.0's `CacheEvictor` callbacks, tracking current spans and enforcing the latest byte limit. It must evict immediately when a new lower limit is applied, not wait for the next cache write.
- Keep the active cache and its directory lock stable while updating the policy. Serialize policy updates with cache callbacks and verify startup, active reads/writes, profile/settings changes, and cache release/recreation.
- Add tests proving that an existing cache owner enforces a changed lower limit immediately, an increased limit is honored on subsequent writes, auto-size recomputation is explicit, and only one SimpleCache owns the directory.

**Evidence and verification boundary**
- Re-fetched `PlaybackBuffering.android.kt`, `PlayerSettingsRepository.kt`, `PlaybackBufferSettings.kt` and the Media3 1.8.0 `LeastRecentlyUsedCacheEvictor.java` and `SimpleCache.java` sources.
- Confirmed the two settings setters only persist/publish; no cache invalidation or reconfiguration call exists in the repository's playback cache owner.
- No production code or tests changed. No Gradle test, build, CI, cache I/O experiment or device benchmark was run.


## Runtime VOD cache-limit implementation and concurrency review (Cycle 140, 21 November 2026)

Implemented the Cycle 139 settings-lifetime fix in the existing Android cache owner, retaining the single process-wide SimpleCache and its directory lock.

**Production change**
- Replaced the immutable LeastRecentlyUsedCacheEvictor with AdjustableLruCacheEvictor, which keeps the same LRU ordering semantics and accepts a new byte limit at runtime.
- The active SimpleCache is not recreated when the configured size changes. A process-lifetime IO coroutine observes the settings flow and applies changes to the existing evictor. The initial get(context, settings) call uses the initial settings snapshot only when creating the cache; after creation, the live repository flow is the sole authority for policy changes.
- Lowering the limit triggers eviction from the IO settings collector, not from the playback setup caller; increasing it affects subsequent writes without clearing existing cache entries.
- Auto-size is recalculated when the auto-size mode or configured-size setting changes, using one-fifth of current usable cache-directory space and retaining the existing 256 MiB–10 GiB clamp. It is not continuously polled for unrelated disk-space changes.
- The cache's LRU tree is updated from Media3's add/remove/touch callbacks. The evictor never holds its own state lock while calling Cache.removeSpan(). This avoids the lock inversion that would occur if a settings thread held the evictor lock while waiting for the synchronized SimpleCache, as callbacks execute under the cache monitor. Media3 1.8.0 SimpleCache.removeSpan() reports removal synchronously, allowing the tracked byte count to converge after each eviction.

**Regression coverage added**
- Added Android host tests for immediate eviction after lowering the limit, allowing subsequent writes after increasing it, LRU recency updates after a span touch, and auto-size calculation/clamps.
- The tests use a focused Cache proxy to exercise the real evictor callback behavior without opening a second SimpleCache or depending on Android filesystem/database setup.

**Review boundaries and remaining verification**
- Exact Media3 1.8.0 CacheEvictor, Cache, CacheSpan, SimpleCache and LeastRecentlyUsedCacheEvictor sources were fetched from the AndroidX repository through Exa. Context7 resolved AndroidX Media3 but returned no matching API documentation for this cache-specific query; no API claims rely on a newer version.
- Source review confirms cache callbacks are invoked synchronously from SimpleCache operations, which are synchronized. The implementation releases its own lock before calling back into the cache.
- The Android host test source was added but not executed. The available GitHub integration can inspect and update remote files but cannot run the repository's Gradle test task. No Gradle task, APK build, CI, benchmark, cache I/O experiment or device run was started.
- The runtime settings collector and interaction with concurrent real CacheDataSource reads/writes still require focused host/integration execution before merge. No playback speedup is claimed; this change corrects settings application only.


## Review correction — stale player settings snapshot on cache reuse (Cycle 141, 21 November 2026)

A second review of the new cache owner found that PlayerEngine captures PlayerSettingsRepository.uiState.value in a remember block. That value is a player-composition snapshot, not a live settings stream. The first Cycle 140 implementation reapplied the settings argument whenever PlaybackDiskCache.get() returned an existing cache.

**Root cause and correction**
- A live settings update could be applied correctly by the StateFlow collector, then later be overwritten by a subsequent get() call carrying the older player snapshot. This could make the cache limit revert while the UI continued showing the new setting.
- The same active-cache branch could call Cache.removeSpan synchronously on the caller that creates playback DataSources. Lowering the limit across many spans could therefore put disk deletion work on a playback setup path.
- Removed the active-cache setMaxBytes call. The initial snapshot is now used only when creating the SimpleCache; after creation, the process-lifetime IO collector is the sole owner of live policy updates and eviction.
- This also prevents a stale player snapshot from undoing a newer repository setting and keeps limit-reduction work off the playback setup caller.

**Verification boundary**
- Confirmed the player settings value is captured with remember and the repository exposes a StateFlow updated by publish(). The live observer collects that flow and maps only the two cache policy fields.
- Re-fetched the modified cache owner after the correction. The Android host tests remain unexecuted; no Gradle task, APK build, CI or device benchmark was run.
- The fix improves correctness and avoids synchronous cache eviction during player setup. It is not evidence of measured playback-startup improvement.
