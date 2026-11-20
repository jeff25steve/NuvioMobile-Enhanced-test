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

