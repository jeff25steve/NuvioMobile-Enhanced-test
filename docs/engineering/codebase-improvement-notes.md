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
- This follow-up was implemented in Cycle 143: one explicit ExoPlayer diagnostic attempt-start helper now covers all six call sites, while keeping player rebuild/listener lifecycle distinct from a new prepare attempt. Event identity correlation and stale-callback tests remain open.
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
- Added Android host tests for immediate eviction after lowering the limit, allowing subsequent writes after increasing it, LRU recency updates after a span touch, auto-size calculation/clamps, and known/unknown write-length reservation behavior.
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


## Review correction — HLS inspection timeout telemetry (Cycle 142, 21 November 2026)

The HLS playlist inspection log previously inferred `timedOut` by comparing total elapsed wall time with the configured timeout. That is not the same event as `FutureTask.get()` throwing `TimeoutException`: scheduling and return overhead can make the elapsed duration cross the threshold after a successful result, or clock granularity can make a timeout appear slightly below the threshold.

**Correction**
- Added an explicit timeout callback to resultWithin(). It runs only in the TimeoutException branch after the worker task is cancelled.
- HLS inspection now logs this explicit outcome instead of deriving it from elapsed milliseconds. Elapsed time remains a separate duration metric.
- Added a host test for the timeout callback firing on timeout and remaining false on a successful fast result.

**Remaining boundary**
- The worker is still interrupted and left daemonized if its DataSource ignores interruption. The existing HLS follow-up remains valid: use a controllable DataSource to test open/read interruption and close behavior before attempting cancellation/close coordination or changing the 3-second timeout.
- The new host test source was added but not run. No Gradle test task, build, CI, network fault injection or device measurement was performed.


## Review correction — ExoPlayer prepare-attempt coverage (Cycle 143, 21 November 2026)

Completed the missing diagnostic attempt boundaries identified in Cycle 138. A single local beginExoPlayerPrepareAttempt(reason, details) helper now increments PlaybackDiagnostics and resets first-load/completion guards immediately before each ExoPlayer prepare sequence.

**Covered call sites**
- Initial media preparation.
- Blu-ray title/recovery preparation.
- Manual retry.
- External subtitle attachment/reload.
- External subtitle removal/reload.
- External subtitle removal followed by built-in subtitle selection/reload.

**Why this matters**
- Previously, only initial media preparation began a new attempt. Loads following retry, disc-title navigation or subtitle-driven MediaItem replacement could be consumed by stale first-event guards or attributed to the previous attempt.
- The helper is called once at each of the six prepare boundaries; MPV retains its separate beginAttempt path.
- Event identity correlation is still not solved. A delayed callback from a previous media item may have a non-negative elapsed timestamp and still be misattributed; the current diagnostics remain provisional until event-specific timeline/window/period identity is correlated.

**Verification boundary**
- Re-fetched PlayerEngine.android.kt and counted six exoPlayer.prepare() calls, each preceded by exactly one beginExoPlayerPrepareAttempt call. Existing PlaybackDiagnosticsTest covers guard reset semantics but was not executed in this session.
- No Gradle task, Kotlin compile, APK build, CI, physical-device playback or startup benchmark was run.



## Android animated-image policy parity audit (Cycle 144, 22 November 2026)

Audited the shared `NuvioAsyncImage` contract against both platform implementations and the repository-pinned Coil version.

**Confirmed implementation mismatch**
- The common `NuvioAsyncImage` API exposes `animateIfPossible` with a default of `false`.
- The iOS implementation branches on that value: when false, it uses the static-image fallback path; when true and the URL looks animated, it uses the bounded animated-frame path.
- The Android implementation accepts `animateIfPossible` but never reads it. It always delegates to Coil `AsyncImage`.
- Android's process-wide `NuvioApplication.newImageLoader()` unconditionally registers `AnimatedImageDecoder.Factory()` on API 28+ and `GifDecoder.Factory()` on older API levels. The pinned `coil-gif` 3.6.3 module automatically detects GIFs from their file headers, and its animated decoder also supports animated WebP (and animated HEIF on supported API levels).
- Consequently, the Android call path does not enforce the shared opt-in flag: animated formats can be decoded as animations even when a caller passes `animateIfPossible = false`. This differs from the explicit iOS policy and can cause unnecessary frame decoding for static-preview use cases.

**Why no production change was made in this cycle**
- Coil 3.6.3 supports a request-specific `decoderFactory`, so the setting can be enforced without disabling the network fetcher globally. However, selecting a safe static decoder is format- and API-sensitive: Android's `BitmapFactoryDecoder` does not establish a supported static-frame path for every animated format (notably GIF on all supported API levels).
- Disabling Coil's service-loader registration globally is not a safe shortcut here: the app relies on its shared loader's custom network fetcher, SVG decoder, poster interceptors and shared cache behavior. A second loader would also introduce cache/lifecycle duplication.
- A correct fix needs an explicit static-frame decoder policy for GIF/WebP/HEIF across minSdk 24 through current Android, while preserving the existing custom fetcher/interceptor chain. That should be implemented with format-specific tests and a platform test proving that false produces a still frame and true animates, rather than replacing the decoder factory with a decoder that may reject GIF data.

**Evidence**
- Re-fetched `NuvioAsyncImage.kt`, `NuvioAsyncImage.android.kt`, `NuvioAsyncImage.ios.kt`, `NuvioApplication.kt`, `PlatformImageLoader.android.kt` and `gradle/libs.versions.toml` from `main-build-testing`.
- The version catalog pins Coil 3.6.3. Context7 resolved and queried the exact `/coil-kt/coil/3.6.3` documentation. The Coil 3.6.3 GIF guide confirms header-based animated-format detection and the platform decoder choices; the exact tagged `BitmapFactoryDecoder.kt` and `ImageRequest.kt` sources confirm the available static bitmap decoder and request-level decoder-factory override.
- Exa reviewed the Coil 3.6.3 GIF guide, Compose `AsyncImage` documentation, decoder API and the upstream discussion of per-request animation policy. The upstream discussion recommends request-specific decoder selection rather than disabling all service-loaded components.
- No production code or tests changed. No Gradle task, Kotlin compilation, build, CI, device playback or image-decoding benchmark was started. This is a confirmed cross-platform behavior gap, not a measured performance regression or a claimed fix.



## Android phone benchmark runner — clean-stage action dispatch review (Cycle 145 correction, 22 November 2026)

**Correction to the earlier finding**
- The earlier claim that `collect` needs the BenchmarkRelease APK pair copied into the staging directory was incorrect.
- Although `apk_paths()` contains `full:collect` and `playstore:collect` mappings, the helper is called only by `install_variant()`. The `collect` action calls `run_instrumentation` directly against the already-installed profile-generation variant; it does not install from those APK paths.
- The `measure` action likewise runs instrumentation against an already-installed BenchmarkRelease variant and does not call `apk_paths()`.
- Therefore, adding `collect` or `measure` to the APK-copy staging case would be unnecessary and would encode the wrong dependency. The earlier proposed clean-stage APK regression for those actions is withdrawn.

**Cycle 149 — CLI help parity implemented on the review branch**
- Added `collect`, `install` and `measure` examples for both flavors to the usage output.
- Documented the required installed-variant preconditions so profile collection and measurement are not presented as standalone install operations.
- Added host regression assertions that invoke `help` through same-directory staging aliases and check the rendered output for these commands.

**Evidence and verification boundary**
- Re-fetched the complete runner and host test. Traced every `apk_paths()` call: the only call is inside `install_variant()`; the `collect` and `measure` dispatcher branches call `run_instrumentation` without invoking installation.
- The host test intentionally performs `install-profile` before profile collection and later exercises measurement against installed-state variants; its shared stage directory does not establish a missing-APK defect for these instrumentation-only actions.
- No production script or test was changed. This correction withdraws the false positive rather than introducing an unnecessary staging change. No test, build, CI, APK, device run, profile collection or benchmark was started.


## Android phone benchmark runner — staging path normalization gap (Cycle 146, 22 November 2026)

**Confirmed defect**
- The runner computes `SCRIPT_DIR` as an absolute directory path, but compares it directly with the raw `NUVIO_PHONE_STAGE_DIR` environment value.
- A relative stage directory is valid when it resolves to a distinct directory. The defect occurs when a relative spelling (from the current working directory) or a trailing-slash spelling resolves to the same directory as `SCRIPT_DIR` but does not compare equal as a string.
- In that same-directory case, the staging branch attempts to copy the runner onto its own file path before re-executing it. POSIX specifies that `cp` must not copy a source onto the same file and may emit a diagnostic; the exact exit behavior is implementation-dependent, so the reliable finding is the missed same-directory guard, not a guaranteed specific `cp` failure. The existing test sets an absolute stage path without a trailing slash, so these aliases are not covered.

**Required regression**
- Exercise the runner with a relative `NUVIO_PHONE_STAGE_DIR` and an absolute path with a trailing slash; both should normalize to the same canonical stage directory and execute the requested action once.
- Retain the existing absolute-path fixture and verify that staging still copies only the action's required APK pair.

**Cycle 148 — implementation on review branch `fix/phone-benchmark-stage-paths`**
- The runner now prefixes relative stage paths with `./` before passing them to `mkdir` (including relative names beginning with a dash), creates the directory, and resolves it with POSIX `cd -P`/`pwd` while suppressing `CDPATH` output.
- The script path is likewise made option-safe before `dirname`, and `SCRIPT_DIR` uses the same physical-directory policy. Trailing-slash and relative aliases of the runner directory therefore compare consistently; distinct relative staging directories remain supported.
- Added host regression fixtures for a trailing-slash alias and a relative path that resolves to the bundle directory. Both invoke the help action and assert it appears exactly once, which catches the previous same-file self-copy/re-exec path.
- The CLI help now documents the supported `collect`, `install` and `measure` commands and their installed-state preconditions (see the Cycle 145 correction section).

**Verification boundary**
- Re-fetched the updated runner and test source from the review branch and inspected the changed regions. At the time this cycle was recorded, a local checkout/command runner was unavailable: GitHub clone failed because this environment could not resolve `github.com`. Neither `sh -n` nor the focused host regression script was executed before integration. The changes were subsequently fast-forwarded into `main-build-testing` at `70c103992c7995b6d449596c205968820aeb655a`; source integration is confirmed, but shell syntax and host regression execution remain unverified. No Gradle task, build, CI, APK, device run or benchmark was started.


## Android phone benchmark runner — normalize staging-directory identity (Cycle 147, 22 November 2026)

**Confirmed path-identity defect**
- The runner computes `SCRIPT_DIR` as an absolute path, but compares it with the unnormalized `NUVIO_PHONE_STAGE_DIR` value.
- If the configured staging path resolves to the same directory but has a different spelling (for example, a trailing slash, or a relative path from a working directory that makes it resolve to the script directory), the string comparison incorrectly enters the staging branch.
- The runner then passes its own script as both the source and destination of the `cp` operation. POSIX specifies that when source and destination refer to the same file, `cp` does not copy it and may emit a diagnostic. The runner's current flow does not correctly identify this same-directory case before attempting the copy/re-exec sequence.
- A relative path that resolves to a genuinely different staging directory is valid and should continue to work; the regression must distinguish that normal case from a relative spelling of the script directory.

**Required regression and correction**
- Normalize the staging directory after creating it, using portable shell directory resolution, before comparing it with `SCRIPT_DIR`.
- Compare both paths using the same physical/logical resolution policy. Avoid introducing non-POSIX utilities or changing symlink semantics unintentionally.
- Add fixtures for an absolute path with a trailing slash and a relative path that resolves to the script directory. Also retain a relative path to a distinct staging directory to prove normal staging still works.
- Assert that the same-directory cases execute the requested action exactly once and never invoke `cp` with identical source/destination files.

**Evidence and verification boundary**
- The original finding was confirmed by re-fetching the top-level guard and existing host fixture. POSIX.1-2024 specifies that when source and destination refer to the same file, `cp` must not copy it and may diagnose the condition.
- The source correction and alias fixtures are integrated into `main-build-testing` at `70c103992c7995b6d449596c205968820aeb655a`. Their execution remains pending because no local checkout/command runner was available in this environment.


### Android HLS repair eligibility and bounded inspection (Cycles 151–152, 22 November 2026)

- Cycle 151 corrected H.264 segment classification: only a second segment whose first coded slice is an IDR (NAL type 5) can trigger SPS/PPS init-map insertion. A P/B first slice depends on reference pictures that SPS/PPS alone cannot restore. Added a non-IDR regression fixture.
- Cycle 152 gave the optional segment-head probe its own HTTP DataSource factory backed by a derived OkHttp client with a 2.5-second whole-call timeout. It inherits the shared playback client's connection pool, dispatcher, DNS, TLS policy and interceptors, including Authorization injection and request headers. The regular HLS playback DataSource keeps its existing 15-second network timeouts; the outer 3-second FutureTask deadline remains as a fallback. The full flavor's YouTube chunked route deliberately retains its prior fallback rather than bypassing its transport.
- Added a host regression fixture verifying that segment probes use the dedicated factory. Host tests and Kotlin compilation remain unexecuted; no build, CI, network fault injection, device playback or benchmark was run. These changes correct repair eligibility and bound optional inspection I/O; no numerical playback improvement is claimed.


### Progressive audio selection and DataSource factory invalidation (Cycles 153–154, 22 November 2026)

- Cycle 153 now classifies video and separate audio URLs independently for VOD buffering. Video response headers/stream type no longer misclassify the separate audio resource; opaque audio URLs without a recognizable MIME hint fail closed and are not assumed progressive. Added host coverage for progressive audio with HLS video, adaptive audio with progressive video, response-header isolation, unknown and blank audio URLs.
- Cycle 154 fixed stale Compose factory captures: the DataSource factory now remembers the complete `playerSourceKey`, the current network-byte counter instance, subtitles, live-stream state and context. This ensures audio URL, stream type and byte-counter changes cannot leave a factory incrementing an obsolete counter or applying stale buffering decisions.
- Compose `remember` key behavior was checked against Android Developers documentation and Context7. The repository pins Compose Multiplatform 1.13.0-beta01; Context7 did not expose that exact prerelease as a versioned entry. Tests and Kotlin compilation remain unexecuted; no Gradle task, build, CI, device playback or benchmark was started. No measured playback speedup is claimed.


### HLS playlist rewrite memory bound (Cycles 155–156, 22 November 2026)

- Cycle 155: known-length HLS manifests larger than 256 KiB bypass the optional SPS/PPS rewrite without reading their bodies during DataSource open().
- Cycle 156: unresolved-length manifests are read only through the 256 KiB + 1-byte sentinel. Oversized manifests replay that exact prefix and continue from the open upstream source, avoiding truncation or data loss. Small manifests retain the existing repair path.
- Added host regression cases for both paths. Media3 1.8.0 DataSource contract was checked from the exact AndroidX source after Context7 returned 1.8.1 rather than 1.8.0. Tests/compilation remain unexecuted; no build, CI, device playback or benchmark was run. No speedup is claimed.


### Media3 1.11.1 playback and local-buffer policy (Cycles 157–158, 22 November 2026)

- Cycle 157 upgrades all catalogued Media3 artifacts together from 1.8.0 to 1.11.1. Upstream changes include a guard on automatic LoadControl memory sizing and custom MIME track classification, eligible-video seek improvements, more efficient late-frame dropping, and further OOM protection under memory pressure. 1.11.1 also fixes Matroska seekability and HLS EOF retry behavior.
- Updated LiveStartLoadControl to forward Media3 1.11.1 player-scoped allocator and preloading calls. Added host tests for delegation.
- Cycle 158 uses Media3's separate local/streaming buffer APIs. Custom and native-memory policies are explicit for both; normal network defaults are streaming-only; the small downloaded-file policy is local-only. content: URIs including DownloadsProvider now follow the local policy, matching Media3's own URI classification. Added host coverage for file/content/HTTP classification.
- Context7 and Exa checked 1.11.1 docs, release notes and tagged source. Tests and compilation were not run; no Gradle task, build, CI, device playback or benchmark was started. No measured speedup is claimed.


### Video software-renderer priority correction (Cycle 159, 22 November 2026)

- The custom `SoftwareVideoRenderer` was appended after `super.buildVideoRenderers()`. That is correct for Media3's `EXTENSION_RENDERER_MODE_ON`, but it silently contradicted `EXTENSION_RENDERER_MODE_PREFER`: Media3 inserts preferred extension renderers before the core MediaCodec renderer, and ExoPlayer's default track selector chooses the first suitable renderer.
- Added a mode-aware insertion index. `PREFER` inserts the app software renderer at index 0, `ON` appends it after the existing renderers, and `OFF` does not register it. This preserves hardware-first behavior in ON mode while making the configured preferred-software mode actually prefer the app decoder for formats it reports as supported.
- Added host regression tests for all three modes. The change is limited to renderer ordering; supported formats, decoder capabilities, codec implementation, and default hardware-first selection are unchanged.

**Evidence and verification boundary**
- Context7 resolved AndroidX Media3 1.11.1 and documented that PREFER places extension renderers before core renderers, while ON places them after. Exa fetched the exact 1.11.1 `DefaultRenderersFactory` source and official Android Developers API reference. The same Media3 docs state that ExoPlayer uses the first renderer supporting the input format when a custom renderer is added.
- Tests were added but not run because no build/test task was authorized. No Kotlin compilation, Gradle task, APK build, CI, device playback or benchmark was started. This is a correction to configured decoder-selection behavior, not a measured frame-rate or startup-time claim.


### FFmpeg software-decoder input safety (Cycles 160–161, 22 November 2026)

- Cycle 160: `SoftwareVideoDecoder` allocated direct `DecoderInputBuffer` instances with the default zero-byte padding. The JNI layer passed the buffer's raw address directly to FFmpeg 8.0 `avcodec_send_packet`. FFmpeg 8.0 requires `AV_INPUT_BUFFER_PADDING_SIZE` (64) additional zero bytes after every compressed input packet; the previous Media3 buffer did not reserve those bytes. The decoder now reserves the required 64-byte tail and explicitly zeroes it for every sample before the native call. It keeps the sample in its original direct buffer and avoids an extra compressed-packet copy.
- Added host regression tests for reserved capacity, zeroed padding, unchanged sample limit/content, and rejection of insufficient capacity.
- Cycle 161: added a JNI-side direct-buffer capacity check before FFmpeg receives the pointer. Native code rejects non-direct, negative-size, or undersized buffers instead of allowing FFmpeg to read beyond the Java buffer allocation.

**Evidence and verification boundary**
- The checked-in FFmpeg headers declare `LIBAVCODEC_VERSION_MAJOR 62` (FFmpeg 8.0). Context7 queried the versioned FFmpeg 8.0 docs; they state that packet input requires 64 additional bytes and warn that non-zero padding can cause overreads and crashes. The exact 8.0 `av_new_packet` implementation was also checked and confirms that FFmpeg's packet allocator zeroes this padding. The exact Media3 1.11.1 `DecoderInputBuffer` source confirms its default constructor uses padding size 0 and its two-argument constructor reserves the requested extra capacity.
- Tests were added but not executed because no Gradle/test task was authorized. No C/C++ compilation, Kotlin compilation, build, CI, device playback or benchmark was started. The buffer contract correction is source-verified; runtime stability and playback impact remain unverified.


### Per-frame video colour metadata (Cycle 162, 22 November 2026)

- Removed the decoder-wide mutable `frame_data_space` value. The decode thread previously overwrote it as soon as each frame was decoded, while the playback thread applied it later when rendering a queued output buffer. When decoding ran ahead, a frame could therefore be rendered with a later frame's Android dataspace (primaries, transfer and range), particularly relevant to HDR transfer metadata.
- `nativeReceiveFrame` now returns the frame's Android dataspace alongside its timestamp, dimensions and Media3 colour-space value. Kotlin stores that value in the corresponding `VideoDecoderOutputBuffer.decoderPrivate` field, and `nativeRender` receives it from that exact output buffer. The native window only changes dataspace when the currently rendered frame requires a different one.
- Added a host regression test proving two queued output buffers retain independent dataspace values when the second frame is decoded before the first is rendered.

**Evidence and verification boundary**
- The exact AndroidX Media3 1.11.1 `VideoDecoderOutputBuffer` source documents `decoderPrivate` as decoder-private per-buffer data and shows output buffers are individually queued and later rendered by `DecoderVideoRenderer`. The app's existing native code computed a complete Android dataspace from each `AVFrame` but stored it on the shared decoder object. The source trace confirms the decode/render hand-off can be asynchronous, so the global field did not preserve frame association.
- A final static diff review also caught and corrected the JNI metadata array declaration to hold all five values written by `SetLongArrayRegion`; this was corrected before branch integration. Test added but not run. No Gradle task, native/Kotlin compilation, build, CI, device playback or visual HDR validation was started. This corrects per-frame metadata association; no measured visual or performance result is claimed.


### Native video dataspace error handling (Cycle 163, 22 November 2026)

- `ANativeWindow_setBuffersDataSpace` returns 0 on success and `-EINVAL` when the window is invalid or the requested dataspace is unsupported. The renderer previously ignored this return value and cached the requested dataspace as active even after failure. It now caches only successful applications and logs a failed dataspace application once per distinct attempted dataspace on the current surface. Surface replacement resets the attempt state, allowing a fresh attempt on the new window.
- Context7 and the Android NDK Native Window API reference confirm the return-value contract. No native compilation, build, CI or device validation was run.

### Playback critical-path and diagnostic audit (Cycle 164, 22 November 2026)

- Rechecked the current Media3 pin: `gradle/libs.versions.toml` specifies 1.11.1. Older 1.8.0 references in earlier dated audits are historical and must not be copied into current implementation decisions.
- Corrected the playback startup note's analytics description. `PlaybackDiagnostics.beginAttempt()` resets first-load and completion guards, and all six prepare call sites use `beginExoPlayerPrepareAttempt()`. The remaining correctness gap is event-to-media ownership: a delayed load callback can still be attributed to a newer attempt without matching its event timeline/window/period to the intended source. Keep these timings provisional until that correlation has a testable implementation.
- Confirmed the standard HLS segment-inspection path uses a dedicated OkHttp client with a 2.5-second whole-call timeout, while the outer `FutureTask` deadline is 3 seconds. This covers standard OkHttp response-body reads; the full-flavor YouTube chunked fallback uses its own transport and must be considered separately. The older generic statement that every timed-out worker can outlive the timeout was too broad.
- Identified a separate local-video entry-path candidate: `playbackMediaItemFromUrl()` can synchronously wait for up to 4 KiB of `file://`/`content://` header inspection through `runBlocking(Dispatchers.IO)` when called from Compose setup. Do not remove this sniff globally: it can prevent misleading `.m3u8`/`.mpd`/`.ism` file names from selecting adaptive MediaSources. Media3 1.11.1 `DefaultMediaSourceFactory` routes adaptive MIME types to adaptive sources and otherwise uses `ProgressiveMediaSource` with `DefaultExtractorsFactory` sniffing.
- Next safe implementation step for the local-file candidate is a test-first fast path that retains payload inspection only when the path-derived MIME would select an adaptive MediaSource, with coverage for correct adaptive files, progressive containers carrying misleading adaptive extensions, content URIs and unreadable providers. No code was changed because the required RED/GREEN regression run could not be performed without a local command runner; no Gradle task, test task, build, CI or device benchmark was started.
- Detailed evidence and the versioned Media3 references are in [player stream startup optimization](player-stream-startup-optimization-2026-11-19.md). This cycle is a source-level audit and documentation correction, not a measured startup improvement.

### Local progressive-video MIME fast path (Cycle 165, 22 November 2026)

- Implemented the Cycle 164 candidate in `PlaybackMediaItems.android.kt`. Local `file://` and `content://` sources with a known progressive path MIME (MP4, Matroska, WebM, TS, MOV, AVI, MPEG, etc.) now skip the pre-prepare 4 KiB payload read and its main-thread `runBlocking(Dispatchers.IO)` wait. Media3's `ProgressiveMediaSource` still performs its normal extractor sniff after its DataSource opens.
- Kept payload inspection for unknown local path MIME values and adaptive-looking paths (`.m3u8`, `.mpd`, `.ism`/`.isml`). This preserves correction of mislabeled adaptive extensions before `DefaultMediaSourceFactory` selects HLS/DASH/SmoothStreaming versus progressive playback. Explicit stream type and response-header MIME hints still take precedence.
- Added `PlaybackLocalMimeInspectionTest` host coverage for progressive local fast-path selection, adaptive extension inspection, unknown local paths and network URI exclusion.
- Evidence: the exact Media3 1.11.1 `DefaultMediaSourceFactory` source and API docs confirm adaptive MIME routing and progressive fallback; the progressive fallback uses `DefaultExtractorsFactory` to sniff the actual container. Context7 resolved the exact 1.11.1 library and Exa retrieved the official source/API contract.
- The host test was added but not executed, and no Gradle task, Kotlin compilation, build, CI, device playback or startup benchmark was run. This removes a known synchronous pre-read from common local progressive paths; the size of the user-visible improvement remains unmeasured. Unknown-extension content URIs may still require inspection and can still block the synchronous caller.

### Native read-ahead memory-window enforcement (Cycle 166, 22 November 2026)

- Fixed a mismatch in `NativeReadAheadDataSource`: the queue was bounded by `capacityBytes`, but each backing allocation was always 16 MiB and each upstream read always requested 32 KiB. This could allocate more mapped memory than the configured queue window, especially for small custom buffer settings, and could overshoot the queue cap by one read.
- Backing blocks are now sized to the lesser of 16 MiB and the remaining configured queue capacity. Upstream reads are also limited to the currently available queue capacity. Chunk fill/consume boundaries now use the actual mapped block capacity, including recycled blocks.
- Added host coverage for small configured windows, remaining-window block sizes, the 16 MiB upper bound and invalid zero-remaining requests. Test not run.
- Android's `SharedMemory.create(name, size)` allocates the requested size and `mapReadWrite()` maps the whole region; the exact Android API confirms this is real mapped native memory, not just a logical queue accounting value. Context7 confirmed the branch's Media3 1.11.1 dependency; Exa retrieved the Android SharedMemory API and Media3 DataSource contract.
- No Gradle task, compilation, build, CI, device playback or memory benchmark was run. The code now bounds the size of new blocks and the bytes requested per read to the configured window; runtime memory savings have not been measured.

### Read-ahead DataSource lifecycle metadata (Cycle 167, 22 November 2026)

- Corrected `NativeReadAheadDataSource` metadata ownership. Its `openedUri` and `openedHeaders` previously remained populated after `close()`, so callers querying a closed wrapper could observe the previous resource's URI and response headers.
- Metadata is now cleared after close, including when close/join exits exceptionally. Before a new open, prior metadata is cleared; if the upstream open fails, the wrapper captures the upstream's attempted-open URI and headers until the caller closes it, matching the Media3 1.11.1 DataSource contract.
- The exact Media3 DataSource API says URI/headers describe the currently open source, and Media3 1.11.1 release notes explicitly require failed-open URI/headers to remain observable until the subsequent close. This avoids stale-source attribution without changing network requests or buffering policy.
- No test, compilation, build, CI or device playback was run. The lifecycle change is source-reviewed only.


### Read-ahead final-block reclamation (Cycle 168, 22 November 2026)

- The native read-ahead consumer recycled a block only when `consumed == block.capacity`. A short final block is smaller than its capacity, so after the reader reached EOF and the consumer drained its written bytes, that mapped block remained retained in the session until `DataSource.close()`.
- The consumer now removes and frees a partially filled block once all written bytes have been consumed and the producer has reached EOF or finished after a terminal read failure. Full blocks continue to enter the reusable block pool; a partial block is never freed while the producer can still append to it.
- Added host coverage for partial-block release after producer completion, retaining a partial block while the producer may append, and recycling a full block before producer completion. Tests not run.
- This is bounded native-memory reclamation at end of input, not a measured playback speedup. No Gradle task, compilation, build, CI, device playback or memory benchmark was run.

### Read-ahead close error propagation (Cycle 169, 22 November 2026)

- `NativeReadAheadDataSource.close()` previously wrapped `upstream.close()` in `runCatching`, silently discarding close failures. That hid resource-cleanup errors from Media3 and made a failed close appear successful.
- Close now always attempts the reader wait even if upstream close fails, then propagates the upstream failure. If both operations fail, the reader-wait failure remains primary and the upstream-close failure is attached as suppressed. URI and response-header metadata are still cleared in `finally`.
- Added host coverage for propagation, ensuring the reader wait still runs, and retaining both failures when both cleanup steps fail. Tests not run.
- Context7's exact Media3 1.11.1 API documents `DataSource.close()` as a potentially failing operation; the 1.11.1 DataSource contract requires callers to close after open failures. Exa inspected the Media3 OkHttpDataSource close implementation and OkHttp response-body cleanup guidance. This preserves cleanup diagnostics; it does not claim faster playback.
- No Gradle task, compilation, build, CI, device playback or benchmark was run.


### Reclaim the reusable native read-ahead pool after producer completion (Cycle 170, 23 November 2026)

- The previous EOF fix released partially filled terminal blocks, but full blocks already returned to `freeBlocks` remained mapped until `DataSource.close()`. Once the producer has reached EOF or terminated, no further reads can reuse those blocks.
- The reader's completion path now frees the reusable pool immediately. A full block consumed after the producer has finished is freed rather than returned to that pool; full blocks remain reusable while the producer is active.
- Added host coverage distinguishing reusable full blocks from terminal full blocks. Tests not run. This shortens native-memory lifetime at EOF; no playback-speed or peak-memory benchmark was run.

### Recover selective buffered sources after close failure (Cycle 171, 23 November 2026)

- `SelectiveBufferedDataSource.close()` previously cleared `active` only after the delegate closed successfully. A thrown close error left the wrapper pointing at the failed delegate, and the same potentially invalid instance could be selected again on the next open.
- The wrapper now clears `active` in `finally` and invalidates only the delegate whose close failed, allowing the next open to create a fresh source. Successful delegates remain reusable, preserving the existing fast path.
- Added a host regression case that simulates a first-close failure, checks that the wrapper no longer exposes the old URI, and verifies that the next open creates a replacement delegate. Test not run. Media3's DefaultDataSource implementation was inspected as a reference for clearing the selected delegate in a `finally` block.

### Apply response-header overrides using HTTP field-name semantics (Cycle 172, 23 November 2026)

- `ResponseHeaderOverridingDataSource` previously merged overrides with case-sensitive map keys. HTTP field names are case-insensitive (RFC 9110 §5.1), so an upstream `content-type` and an override `Content-Type` could coexist. Consumers could then observe the upstream MIME value instead of the intended override, affecting MIME inference and adaptive source selection.
- Overrides now remove every existing key equal under case-insensitive comparison before inserting the override. Empty overrides return the upstream map unchanged.
- Added host coverage for differently cased keys, duplicate case variants, preserving unrelated headers and the no-copy empty-override path. Tests not run. RFC 9110 §5.1 was checked through Exa.

### Clean up partially created native read-ahead allocations (Cycle 173, 23 November 2026)

- Native block allocation could leak its SharedMemory descriptor if `mapReadWrite()` failed. The pre-27 file-backed fallback could also leave its temporary file or RandomAccessFile open if opening or mapping failed.
- Allocation now closes/unmaps any resource acquired before a failed mapping, removes the fallback temporary file, and preserves the original allocation failure while attaching cleanup failures as suppressed exceptions. Successful block release also retries deletion of the fallback file.
- Added host coverage for cleanup on allocation failure, preserving both the original and cleanup failures, and not running cleanup on success. Tests not run. The official Android SharedMemory API confirms `close()` and `unmap()` are separate lifecycle operations and mappings remain valid until unmapped.
- No Gradle task, Kotlin compilation, build, CI, device playback or benchmark was run for these cycles.


### Complete SharedMemory cleanup even when unmapping fails (Cycle 174, 23 November 2026)

- The native allocation cleanup added in Cycle 173 still called `SharedMemory.close()` only after `SharedMemory.unmap()` returned normally. If unmapping failed, the backing file descriptor could remain open despite the allocation failure being reported.
- Shared-memory cleanup now always attempts descriptor close after unmap, both for failed allocation rollback and normal block release. If both operations fail, the descriptor-close failure is propagated with the unmap failure attached as suppressed. The file-backed fallback likewise attempts temporary-file deletion even when closing its RandomAccessFile fails.
- Added host coverage for descriptor close after unmap failure and preservation of both cleanup failures. Tests not run. Android's SharedMemory API specifies that closing the descriptor does not itself unmap existing mappings, so both operations remain necessary.
### Make seek-preview teardown cancel promptly and reject stale work (Cycles 175–180, 23 November 2026)

**Cycle 175 — cancel a pending ExoPlayer frame wait**
- The ExoPlayer-backed seek-preview source inherited the default no-op SeekPreviewFrameSource.cancel(). Its CompletableFuture.get() wait could therefore remain blocked for the full 20-second frame timeout after the controller asked it to cancel.
- The source now cancels the pending frame future, immediately unblocking the waiting decode thread with CancellationException. Decoder release uses the same cancellation path.

**Cycle 176 — detach the exact source before asynchronous close**
- releaseConnection() and dispose() previously scheduled a coroutine that read the mutable source field later. If a new source was installed before that coroutine ran, cleanup could close the replacement rather than the source being released.
- Teardown now increments the generation, detaches the current source and resets open state synchronously under a lock. It cancels that captured instance immediately and closes that same instance asynchronously. A source that finishes opening after its generation was invalidated is rejected and closed rather than installed.

**Cycle 177 — reject results from an invalidated source generation**
- The frame worker now carries the generation captured before decoding and checks it again before treating a result as success or failure, caching the bitmap, or publishing it to Compose state.
- A late frame or late failure from a released/disposed source can no longer overwrite the current preview or count against the replacement source's failure budget.

**Cycle 178 — stop native thumbnail decode and delivery after feed close**
- Native thumbnail feed closure is now an atomic, immediate state transition instead of being set only after its queued native close operation runs.
- The JPEG decode path checks that state both before and after decoding, and main-thread success/error callbacks check it again before delivery. Late callbacks no longer spend CPU decoding thumbnails for a closed preview or update a disposed controller.

**Cycle 179 — make the initial frame cancellable too**
- The first ExoPlayer grabber is now published to the source as soon as it is constructed, before waiting for its first frame. Previously the source did not retain the grabber until after the first-frame wait returned, so cancellation could not reach the initial wait.
- Grabber release is idempotent to safely handle cancellation, timeout cleanup and normal close racing with one another.

**Cycle 180 — suppress queued open errors after close**
- The asynchronous feed-open failure callback now checks the same closed flag on the main thread, matching the success callback and preventing a late error from reopening fallback work after teardown.

**Cycle 181 — do not reattach a grabber after teardown**
- The initial open path already publishes the new grabber inside openAndAwait(), before waiting for its first frame. A second assignment after openGrabber() returned was redundant and could race with close(), reattaching a grabber that had just been detached and released. Removed the second assignment so source ownership has one publication point.

**Cycle 182 — keep the preview worker alive after expected source cancellation**
- Source teardown can now cancel an in-flight frame wait without cancelling the long-lived StateFlow collector. The worker first checks its own coroutine Job is still active; if cancellation came from an already-invalidated source generation, it discards that request and continues collecting. Actual worker/scope cancellation still propagates immediately.
- Added a host regression contract distinguishing stale-generation cancellation from cancellation in the current generation. Test not run.

**Regression coverage added (not executed)**
- Added composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/seekpreview/SeekPreviewLifecycleTest.kt covering pending-future cancellation, stale/disposed generation rejection, skipping thumbnail decode after close, and discarding a decode that overlaps close.
- Context7 was attempted for the coroutine and API documentation but its monthly quota was exhausted. The repository's pinned versions were checked directly (kotlinx-coroutines 1.10.2 and Media3 1.11.1). Exa retrieved the official Kotlin coroutine cancellation documentation and Oracle CompletableFuture API documentation; Oracle specifies that cancelling a CompletableFuture completes it with CancellationException and unblocks waiters. No dependency versions or APIs were changed.
- These changes target seek-preview cancellation latency, stale-frame correctness and avoiding obsolete thumbnail CPU work. No measured latency or battery improvement is claimed.
- No tests, Kotlin compilation, Gradle tasks, builds, CI, device playback, Macrobenchmark or Perfetto capture were run. Static review and remote-source verification only.
### Improve software video color conversion, native surface safety and frame timing (Cycles 183–187, 23 November 2026)

**Cycle 183 — preserve a usable timestamp when FFmpeg omits frame PTS**
- The JNI frame metadata previously collapsed AVFrame.pts and best_effort_timestamp into one value. When both were AV_NOPTS_VALUE, Kotlin passed Long.MIN_VALUE into Media3 as the output frame presentation time, which could make the frame fail the output-start-time check or carry an invalid timestamp.
- Native frame metadata now returns PTS and best-effort timestamp independently. Kotlin prefers PTS, then best-effort timestamp, and uses the input packet's Media3 timestamp only when FFmpeg has neither.
- Added host tests for all three precedence cases. Tests not run.

**Cycle 184 — configure FFmpeg YUV matrix and range explicitly**
- The software decoder converted source frames to YUV420P with swscale defaults, while separately attaching a dataspace derived from the source frame. The conversion path did not explicitly provide the source matrix or range, risking a mismatch between converted pixel values and the output dataspace on BT.709, BT.2020 and full-range material.
- The native converter now selects FFmpeg's ITU-601, ITU-709 or BT.2020 coefficient table from the decoded frame metadata and explicitly applies the frame's full/limited range to both sides of the YUV conversion. Failure to configure the conversion is surfaced as a frame-conversion failure instead of silently using mismatched defaults.
- FFmpeg 8.0's official libswscale API documents sws_getCoefficients() and sws_setColorspaceDetails(), including the source and destination range flags. No HDR tone-mapping claim is made; this change corrects matrix/range configuration for the existing 8-bit YUV output path.

**Cycle 185 — always unlock a successfully locked native surface buffer**
- nativeRender() previously returned an error if ANativeWindow_lock() succeeded but supplied a null buffer.bits pointer. That return path skipped ANativeWindow_unlockAndPost(), leaving a successfully acquired window buffer locked and potentially blocking later frame presentation.
- The successful-lock/null-bits error path now attempts unlockAndPost() before returning, and logs an unlock failure. A failed lock is still not unlocked; the existing -ENODEV surface-loss path still releases the native window and drops the frame.
- Android NDK documentation defines unlockAndPost() as the operation that unlocks a surface buffer after it has been locked.

**Cycle 186 — declare the packet timestamp time base to FFmpeg**
- The JNI bridge writes Media3's microsecond presentation timestamps directly into AVPacket.pts, but the AVCodecContext did not set pkt_timebase. FFmpeg documents pkt_timebase as the time base in which input packet PTS/DTS values are expressed and requires decoders to set it.
- The decoder now sets pkt_timebase to 1/1,000,000 before avcodec_open2(), matching the microsecond values supplied by Kotlin and making FFmpeg's output timestamp interpretation explicit.

**Cycle 187 — retain the correct color-space slot in Media3's output buffer**
- Expanding the JNI metadata to return separate PTS and best-effort timestamps shifted the frame-info array indices. Review caught that initForYuvFrame() was temporarily receiving the frame height in its colorspace argument.
- The decoder now reads the colorspace from its dedicated metadata slot through a small helper, and a regression test uses distinct height/colorspace values to catch this exact index mix-up.

**Validation and remaining decoder limitation**
- Media3 1.11.1's official SimpleDecoder source was inspected. Its decode loop handles an end-of-stream input buffer directly by emitting an EOS output buffer; it does not invoke the subclass decode() method for EOS. Therefore the current SimpleDecoder-based FFmpeg wrapper cannot drain delayed B-frames by adding a flush branch inside SoftwareVideoDecoder.decode(). Correct FFmpeg EOS draining needs a deliberate Decoder implementation or another architecture that can intercept EOS; that larger redesign is recorded as a follow-up rather than hidden behind an ineffective patch.
- Context7 resolved and queried AndroidX Media3 1.11.1, Android NDK native-window reference documentation and FFmpeg 8.0. Exa independently retrieved the official FFmpeg 8.0 send/receive and libswscale references and Android NDK native-window documentation. The repository pins Media3 1.11.1 and includes FFmpeg 8.0 headers in the native target.
- Added SoftwareVideoFrameTimestampTest.kt with four host regression cases covering PTS precedence, best-effort fallback, packet timestamp fallback and colorspace metadata indexing. Tests not run.
- No CMake/NDK build, Kotlin compilation, Gradle task, test execution, CI, device playback, HDR validation or performance benchmark was run. Source-level review and remote GitHub verification only; no measured playback improvement is claimed.

### Software video output-buffer and YV12 surface validation (Cycles 188–191, 23 November 2026)

**Cycle 188 — validate I420 output-buffer layout in Kotlin**
- Added an overflow-aware requiredSoftwareYuv420BufferSize() helper. It rejects non-positive dimensions, undersized luma/chroma strides, arithmetic overflow and capacities that cannot fit a Java ByteBuffer.
- The decoder checks the allocated direct output buffer's capacity before passing it to FFmpeg. Surface rendering repeats the check before crossing JNI, so both conversion and presentation boundaries enforce the expected I420 layout.

**Cycle 189 — validate direct-buffer capacity again at the JNI boundary**
- nativeConvertFrame() and nativeRender() now obtain the direct-buffer capacity and validate it against the required I420 byte count before calculating plane offsets or calling swscale/copying pixels.
- A non-direct buffer, negative JNI capacity, invalid stride or undersized buffer is rejected before native pointer arithmetic. The size calculation uses checked size_t multiplication/addition to avoid wraparound.

**Cycle 190 — reject incomplete native-window buffers instead of posting clipped frames**
- nativeRender() previously clipped the copy to the smaller of the requested frame and locked buffer dimensions. That could post a partial image if the native window returned an unexpected geometry.
- It now validates the locked buffer dimensions and luma stride before writing any plane. Invalid locked buffers are unlocked and rejected rather than publishing a partially copied frame.
- The JNI surface global reference is also checked; if NewGlobalRef() fails after acquiring the native window, both acquired resources are released.

**Cycle 191 — enforce the Android YV12 layout contract**
- Android's YV12 layout requires even width and height and a 16-pixel-aligned luma stride. The native renderer now rejects odd frame dimensions before requesting YV12 geometry, and validates the returned buffer's even dimensions and 16-aligned stride before calculating chroma-plane offsets.
- Kotlin applies the even-dimension check before calling JNI. The I420 conversion helper still supports odd frame sizes; the stricter rule applies only to the YV12 native-window presentation path.
- Added host regression cases for odd-sized I420 buffer calculations, invalid/undersized strides, Java ByteBuffer capacity limits, and even-dimension YV12 surface requirements. These tests were added but not executed.

### Evidence and verification boundary
- Context7 resolved Android NDK reference documentation and confirmed that ANativeWindow_lock() returns a buffer for CPU writing and ANativeWindow_unlockAndPost() is the required unlock/post operation. The repository's Media3 version remains 1.11.1; no Media3 API or dependency was changed.
- Exa retrieved the Android platform ImageFormat.YV12 definition and AOSP NdkImage YV12 plane-layout handling. Those sources specify even dimensions, 16-pixel luma-stride alignment, c_stride = ALIGN(y_stride / 2, 16), and the Y/Cr/Cb plane offsets. The native renderer now validates the constraints it relies on before writing.
- This is defensive correctness and native-memory safety work at the software-decoder output boundary. It is not a measured frame-rate, startup or playback-smoothness improvement.
- No Kotlin tests, Gradle tasks, CMake/NDK build, compilation, CI, APK, device playback, HDR validation, Macrobenchmark or Perfetto capture was run. Static source review and GitHub re-fetch only.
- The separate FFmpeg send/receive queue issue remains open: FFmpeg 8.0 requires receiving available frames until EAGAIN before retrying a packet and supports zero or multiple output frames per input packet. The current SimpleDecoder wrapper still cannot intercept its EOS input to drain delayed B-frames. A correct fix requires a deliberate decoder/renderer queue redesign; these buffer guards do not claim to solve it.

### Software decoder queue, multi-frame delivery and EOS drain (Cycles 192–198, 23 November 2026)

**Cycle 192 — replace the one-input/one-output SimpleDecoder wrapper with a bounded asynchronous Decoder**
- Replaced SimpleDecoder inheritance with the Media3 Decoder interface and a dedicated worker thread. This is necessary because SimpleDecoder consumes EOS before invoking the subclass decode method, preventing FFmpeg drain signaling.
- Preserved the renderer's callback/executor contract and input/output buffer ownership. Six direct input buffers and six reusable output buffers bound queue memory and apply backpressure when the renderer is holding frames.
- Input buffers now grow on demand instead of preallocating the previous default 768 KiB for every slot. Media3's DecoderInputBuffer still reserves FFmpeg's 64-byte packet padding.

**Cycle 193 — retain packets across EAGAIN and drain every available frame**
- Native packet submission now returns a distinct EAGAIN status instead of internally receiving one frame and retrying once. The Kotlin worker retains the exact input buffer until FFmpeg accepts that packet.
- After an accepted packet, the worker repeatedly receives and publishes frames until FFmpeg returns EAGAIN. It does not submit the next packet while output remains available in FFmpeg, and it pauses receiving when the bounded output pool is exhausted.
- A decoded AVFrame remains owned by the native decoder until conversion or explicit discard completes. New JNI discard support consumes seek-preroll frames without converting them.

**Cycle 194 — send an explicit FFmpeg end-of-stream packet and drain delayed frames**
- EOS input is now handled by the custom Decoder rather than SimpleDecoder. The worker sends a null AVPacket, retries it only after receiving pending output when send returns EAGAIN, then receives until AVERROR_EOF.
- The Media3 EOS output buffer is queued only after FFmpeg reports EOF, so delayed B-frames and other buffered frames are delivered before the renderer sees end-of-stream. The same path is used when DecoderVideoRenderer signals EOS before a format-driven decoder replacement.

**Cycle 195 — serialize flush, seek and release against native decode work**
- Decoder flush is a non-blocking worker barrier. It immediately returns queued/dequeued input ownership and releases queued outputs, marks the new decode epoch, then lets the worker finish any in-flight native call, release the pending packet, call avcodec_flush_buffers() and reset queue/drain state before processing new input. This follows SimpleDecoder's non-blocking flush behavior and avoids making the playback thread wait for software decode during seeks.
- The decoder preserves the Media3 setOutputStartTimeUs contract: the start timestamp can be changed before the first queued input or after a completed flush, not merely when the input pool happens to become empty.
- Native release is serialized on the worker after it exits. Callback dispatch is suppressed while flushing or after release, and the renderer clears its reference after releasing the decoder.

**Cycle 196 — avoid YUV conversion when a frame cannot be presented**
- Frames earlier than Media3's output start timestamp are discarded before swscale conversion, while FFmpeg still processes reference pictures to preserve subsequent decode correctness.
- DecoderVideoRenderer intentionally leaves its underlying decoder output mode unchanged when output is removed. SoftwareVideoRenderer now observes MSG_SET_VIDEO_OUTPUT and sets the software decoder mode to NONE when the payload is cleared or unsupported. Frames decoded during that interval are discarded without allocating/converting I420 output; conversion resumes when a valid Surface or VideoDecoderOutputBufferRenderer is attached.
- Output mode is snapshotted once per frame to avoid inconsistent mode checks during a surface transition.

**Cycle 197 — reconcile input samples with decoded frames at drain completion**
- The custom decoder tracks total queued input samples and produced frames. At FFmpeg EOF, any remaining input samples that produced no frame are reported through skippedOutputBufferCount on the EOS output, keeping DecoderVideoRenderer's in-flight buffer accounting aligned for format transitions and stream end.
- Skipped-output counts now saturate at Int.MAX_VALUE instead of overflowing. Regression tests cover packets retained over EAGAIN, multiple frames per packet, output-pool backpressure, EOS retry/drain, flush reset, no-output/seek skipping, sample reconciliation and counter saturation.

**Cycle 198 — release the renderer's last decoder reference**
- SoftwareVideoRenderer now clears its decoder field in a finally block after the base renderer releases the decoder. The decoder detaches its callback/executor and clears queued/dequeued buffers at release, preventing stale renderer references and buffer pools from being retained across format changes or disable/re-enable cycles.

**Cycle 199 — make flush non-blocking on the playback thread**
- The first custom-Decoder implementation waited for the worker to finish its in-flight native operation before returning from flush(). Review against Media3's SimpleDecoder flush contract showed this could stall the playback thread during a seek.
- flush() now immediately clears queued input/output ownership, clears stale decoder exceptions and posts a flush barrier. The worker completes the current native call, discards its stale output, releases any pending input, calls avcodec_flush_buffers() and processes newly queued input only after the barrier.
- New-epoch input can be queued immediately after setOutputStartTimeUs(); the worker prioritizes the flush barrier, and it dispatches input-available again after completing the barrier so the renderer can refill any buffer that was still in flight.
- Decode failures from the stale pre-flush operation are suppressed rather than reported against the new seek position. No build or seek benchmark was run, so reduced seek stalls are an architectural expectation, not a measured result.

**Cycle 200 — fail fast on an impossible dual-EAGAIN decoder state**
- FFmpeg 8.0 explicitly forbids send and receive from both returning EAGAIN at the same time. The flow state now records when a send was blocked and requires at least one received frame before accepting receive-side EAGAIN and retrying the packet/EOS signal.
- Added a regression test for this invalid state so a future API or state-machine regression fails explicitly instead of allowing an endless send/receive retry loop.

**Cycle 201 — report native flush failures without retrying a broken barrier forever**
- Worker failures from the flush action are now distinguished from stale decode failures. A native flush failure clears the pending barrier, releases queued input ownership and is surfaced through the Decoder exception callback instead of being suppressed as pre-seek work and retried indefinitely.
- Errors from an in-flight decode operation that belongs to the old seek epoch remain suppressed when a flush is already pending.

**Cycle 202 — use Media3 scrubbing mode for interactive ExoPlayer timeline drags**
- The standard, legacy and folded Compose timelines now report drag lifecycle separately from ordinary touch/tap interactions. ExoPlayer enters scrubbing mode only while the user is actively dragging; it receives seek updates during the drag and a final seek on release.
- Tap-to-seek, MPV playback and party-locked seeking retain their existing single-seek behavior. The shared controller API defaults to a no-op so engines without a matching native mode are unaffected.
- Scrubbing mode is explicitly disabled when the timeline interaction ends or the ExoPlayer surface is disposed.

**Cycle 203 — scale scrubbing seek tolerance to the actual timeline width**
- The timeline measures its laid-out width and converts a 2dp touch tolerance into Media3's fractional seek tolerance. The fraction is recomputed when width or display density changes, avoiding a fixed time tolerance that would be inaccurate across short and long media.
- The player clears the temporary fractional tolerance when scrubbing ends, restoring the player's ordinary seek parameters for normal playback and isolated seeks.
- Added host tests for drag-only activation, disabled/zero-duration cases, density-independent tolerance and invalid layout measurements.

**Cycle 204 — keep remote Cast playback on its existing seek path**
- Added an explicit player-controller capability for interactive scrubbing rather than inferring it only from the underlying engine name.
- The Cast-aware controller disables the capability while a remote session is active and prevents scrubbing-mode commands from reaching the paused local ExoPlayer. This also prevents repeated drag updates from becoming a burst of remote Cast seek requests.
- The default capability is enabled only for ExoPlayer-backed controllers; MPV and other implementations remain on their existing behavior.

**Cycle 205 — make scrub cleanup safe across player disposal**
- The ExoPlayer controller now ignores late timeline cleanup callbacks after its player has been released. The release path explicitly exits scrubbing mode before marking the instance released and calling release(), preventing a disposed timeline from sending a command to a dead player.

**Cycle 206 — honor Android's explicit frame-rate matching opt-out**
- The ExoPlayer surface now reads Android 12+ DisplayManager's Match content frame rate preference and disables Media3's Surface frame-rate requests when the user selects Never. On older Android versions, unknown preference values and allowed preferences, the existing Media3 seamless-only strategy remains in place.
- Deliberate compatibility boundary: Media3 1.11.1 exposes OFF and ONLY_IF_SEAMLESS strategies, but no ALWAYS strategy. This change does not force non-seamless refresh-rate switches or claim to implement the system's Always behavior; that would require app-managed Surface.setFrameRate handling using a reliable source frame-rate signal and explicit surface lifecycle cleanup.
- Added host coverage for Never, Seamless only, Always, and unknown preference mapping.

**Cycle 207 — reduce idle ExoPlayer snapshot wakeups**
- ExoPlayer's UI snapshot loop now keeps its 250ms cadence while actively playing, but stops polling entirely while paused. Player.Listener publishes snapshots immediately for state changes, position discontinuities and timeline changes; the 250ms ticker is active only while the player is actually playing.
- Added immediate snapshot publication for position discontinuities and timeline changes, so paused seeks and duration/timeline updates do not wait for the slower fallback poll.
- Added host tests for the active polling interval and the absence of an idle polling interval.

**Cycle 208 — transfer software-decoded frames without an intermediate pixel buffer**
- The Media3 surface-YUV path now transfers a reference-counted FFmpeg AVFrame through VideoDecoderOutputBuffer.decoderPrivate and initForPrivateFrame(). The frame remains owned until Media3 renders or releases that output buffer.
- This removes the per-frame Java direct YUV buffer allocation and avoids the former full-frame copy from that buffer into the Android surface. The alternate VIDEO_OUTPUT_MODE_YUV path retains the existing direct-buffer conversion for GL/output-buffer renderers.
- Private-frame ownership is transferred exactly once, cleared before output-buffer reuse, and released when the renderer releases or drops a frame. Queued frames are also released on flush, decoder failure, and decoder shutdown. The fixed six-output-buffer pool bounds outstanding native frame references.

**Cycle 209 — convert directly into the device's locked YV12 surface**
- The native renderer now passes the locked ANativeWindow_Buffer planes and actual device strides directly to FFmpeg sws_scale(). It writes the Y plane and the reversed V/U chroma planes in the Android YV12 layout, eliminating the intermediate-frame copy.
- The native path validates the actual surface pixel format, even frame dimensions, buffer geometry, stride alignment, chroma stride, and plane-offset arithmetic. Every successful surface lock is paired with unlock/post, including validation and conversion failures.
- The existing FFmpeg color matrix/range handling and Android dataspace selection are retained, now using the retained AVFrame's metadata at render time.

**Cycle 210 — keep output modes and native-frame ownership isolated**
- Private AVFrame ownership is used only for VIDEO_OUTPUT_MODE_SURFACE_YUV; the VIDEO_OUTPUT_MODE_YUV path continues to use Media3's regular YUV planes and dataspace metadata. This avoids treating the legacy dataspace value as a native pointer.
- A reused output buffer has its previous private value cleared before a new frame is initialized. Odd-sized frames are rejected before private-frame transfer because the YV12 surface layout requires even dimensions.
- Added host coverage for the surface-only private-frame mode and one-time native handle transfer. These tests were added but not executed.

**Cycle 211 — release retained GL buffers when switching to native surface output**
- When a pooled decoder output switches from the GL/YUV data-buffer mode to native surface mode, the decoder now clears the previous direct ByteBuffer and its plane/stride views before retaining the AVFrame. This prevents an old full-frame YUV allocation from remaining reachable in a reused surface output buffer.
- Added host coverage proving all three retained YUV storage references are cleared during the mode transition. The test was added but not executed.

### Evidence and validation boundary
- Context7 resolved the repository's AndroidX Media3 1.11.1, Kotlin 2.4.10 and FFmpeg 8.0 documentation. The exact Media3 1.11.1 Decoder, SimpleDecoder, DecoderVideoRenderer, DecoderInputBuffer and VideoDecoderOutputBuffer sources were inspected. Kotlin's JVM interop guidance was used for the Java monitor wait/notify implementation.
- Exa independently retrieved FFmpeg 8.0's official send/receive API documentation. It confirms that a send-side EAGAIN requires receiving output before retrying the same packet; receive must be repeated until EAGAIN; a packet may produce zero or multiple frames; and EOS requires a null packet followed by receive calls through AVERROR_EOF. The AndroidX renderer source confirms that its callback executor wakes the playback thread and that it queues EOS during decoder replacement.
- The software surface path now removes a per-frame Java YUV allocation and an intermediate full-frame surface copy by construction. The real-world frame-time, dropped-frame, CPU, memory and thermal impact remains unmeasured; no measured smoothness or performance percentage is claimed.
- Added host regression tests for the playback and decoder changes (cycles 202–211) but did not execute them. No Gradle task, Kotlin compilation, CMake/NDK build, CI, APK, device playback, HDR validation or benchmark was run, as requested.
- The output-count reconciliation is aggregate: it accounts for input samples that produce no frame once draining completes. Media3's renderer itself tracks buffers per input sample, so codec-specific cases where a single compressed sample expands into multiple independent video frames remain a renderer-contract edge case to validate with real media before claiming universal coverage.


**Cycle 212 — reuse native AVFrame owners across Media3 output-buffer reuse**
- Added a bounded six-entry native frame-handle pool, matching the decoder's six output buffers. A released surface output returns its AVFrame owner struct to the pool; the next surface frame reuses it with av_frame_unref() before av_frame_ref() attaches the newly decoded frame's reference-counted pixel buffers.
- The native transfer consumes a reused handle on both success and failure. Decoder shutdown drains pooled handles; output frames released after shutdown are freed directly. The GL/YUV output mode remains unchanged.
- Added host coverage for FIFO reuse, empty acquisition, capacity bounds and shutdown draining. The tests were added but not executed.

**Cycle 213 — cache swscale color-matrix configuration between metadata changes**
- The direct YV12 surface path and legacy GL/YUV conversion path cache swscale matrix/range configuration by source dimensions, pixel format, colorspace and full/limited range.
- FFmpeg's coefficient lookup and sws_setColorspaceDetails() are performed only when one of those inputs changes, rather than for every decoded frame. The actual matrix/range values and pixel conversion path are unchanged. Cycle 216 then isolates each cache in its own context to preserve this optimization without cross-thread sharing.

**Cycle 214 — recover the missing attempt-safe playback diagnostics integration from PR #31**
- The current branch already contained PR #31's separate-audio buffering/source-selection work, but its load diagnostics still used one boolean per data type. The missing task correlation was ported without replacing the newer source-selection implementation.
- Initial manifest loads and audio/video media loads are now tracked by loadTaskId and track type. Completion events must match the exact task that started in the current prepare attempt; stale completions from an earlier attempt are rejected using LoadEventInfo.elapsedRealtimeMs minus loadDurationMs.
- Rebuffer starts/completions are independently tracked per media track and task, including completion callbacks that arrive after the player has returned to READY. Added host coverage for stale completions, task mismatches, separate audio/video tracks and rebuffer completion ordering.

**Cycle 215 — honor Android's explicit Match content frame rate = Always preference**
- On Android 12/API 31+, the Always preference now uses the displayed PlayerView SurfaceView's Surface.setFrameRate() with the exact input Format.frameRate, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE and CHANGE_FRAME_RATE_ALWAYS. Media3 remains seamless-only until the app-managed non-seamless request is eligible; only while that direct request is active is Media3's strategy switched OFF, preventing competing requests.
- Surface requests are cleared when playback is paused, idle or ended, and re-applied after Surface creation/reconfiguration. Buffering retains the request. Live playback and VOD with a known duration of at least five minutes are eligible; short VOD clips retain seamless matching to avoid disruptive non-seamless switches for brief content. The latest input frame rate is retained so a SurfaceView attached after the format callback still receives the correct request.
- Never still disables frame-rate matching. Seamless-only and unknown preferences continue using Media3's seamless-only strategy. Added host coverage for preference routing, playback-state gating and the long-content boundary.

### Follow-up evidence and validation boundary (cycles 212–215)
- Context7 was used for AndroidX Media3 1.11.1 and FFmpeg 8.0. The FFmpeg documentation supports reusing AVFrame structs by unreferencing before taking a new frame reference. Android platform SDK documentation was not indexed by Context7, so the Surface.setFrameRate overload, strategy constants, clear-with-zero behavior and long-running-video guidance were checked against the official Android Developers Surface API reference and media frame-rate guide. AndroidX Media3 source was inspected for PlayerView's default SurfaceView and AnalyticsListener.onVideoInputFormatChanged().
- AndroidX Media3 LoadEventInfo source confirms elapsedRealtimeMs is the timestamp of each load event, loadDurationMs is the duration up to that event, and loadTaskId identifies the load task. This supports rejecting stale completion events by subtracting duration from the completion event timestamp.
- The software-decoder changes remove per-frame AVFrame struct allocation/free and repeated swscale matrix configuration by construction; the Always path adds the system-requested refresh-rate mode switch where supported. No CPU/frame-time, dropped-frame, thermal or visual improvement is claimed as measured.
- Host regression tests were added first, but not executed. No Gradle tests, Kotlin compilation, CMake/NDK build, CI, APK, device playback, or benchmarks were run. Static source inspection only; runtime/device validation remains required before treating these changes as release-verified.


**Cycle 216 — isolate swscale contexts across decoder and playback threads**
- The worker thread can convert a GL/YUV output while the playback thread renders a previously queued private AVFrame to the Android surface. Both paths previously shared one mutable SwsContext and called sws_getCachedContext/sws_scale concurrently.
- Added independent SwsConversionState instances for GL/YUV conversion and direct-surface rendering. Each owns its own SwsContext and cached matrix/range key; decoder destruction frees both contexts.
- This removes the cross-thread context mutation/reconfiguration race during output-mode changes while preserving the existing conversion algorithm and per-path metadata cache.

- Exa inspected FFmpeg's libswscale implementation: sws_scale mutates per-context internal state, and sws_getCachedContext can replace/free the supplied context when parameters change. Separate contexts are therefore used for the concurrently active output paths rather than sharing one mutable instance.


### Branch and pull-request integration audit — 23 November 2026
- Started from `main-build-testing` at `6a38b85c206d5a68ae3262ef278c2b68557c34df`. The playback feature branch was 18 commits ahead and zero behind. It was fast-forwarded without force to `675ab868e51bf0791d3250d91cd1efca50c74cf2`; GitHub now reports `main-build-testing` and the feature branch identical.
- The repository has no open pull requests. The PRs with an actual GitHub merge timestamp are #12, #14, #15, #17 and #18.
- Playback PRs #31–#34 are closed without a GitHub merge. Their source state was checked rather than assuming closure meant integration: #32's bounded HLS rewrite and #33's Media3 1.11.1 upgrade are already present; #34's scrubbing/frame-rate/idle-polling work (cycles 202–211) is already present; #31's separate-audio source-selection work is present but its task-correlated diagnostics were missing and are now ported as cycle 214.
- Download/network PRs #19–#30 are also closed without merge and are outside this playback change set. The audit found outstanding work still not integrated: the unknown-SAF-size export guard from #25/#27, generation-guarded store removal from #26/#29, per-file keyed lock integration from #22/#23/#30, and duplicate network-interceptor removal from #19–#21/#30. The strong-validator/weak-ETag resume check from #24/#28 is present in the current transfer code. These closed PRs must not be described as merged.
- PR #11, titled “Merge upstream enhanced: integrate 206 missing commits”, is closed without merge. The live branch comparison now reports `enhanced` 3,155 commits ahead and `main-build-testing` 11 commits ahead of it. The closure comment states the legacy React Native app is being discontinued for a Compose Multiplatform rewrite and that rewrite PR intake is not open. No bulk merge was attempted because this is a large, divergent codebase transition, not a safe fast-forward or a playback-only integration.
- PR #31 and #34 also carry the maintainer wind-down notice. No replacement PR was opened. This work was integrated only into the user's `main-build-testing` branch as requested.
- The new head has no associated GitHub Actions workflow runs. No build, test task, CI workflow, APK generation or device validation was started.


**Cycle 217 — enable Media3 hardware-video duration-to-progress scheduling**
- The custom SubtitleOffsetRenderersFactory now enables setEnableMediaCodecVideoRendererDurationToProgressUs(true) only on Android 12/API 31+, where Media3's asynchronous MediaCodec queueing is enabled by default. This lets the hardware video renderer report the next output-frame progress deadline to Media3's dynamic playback scheduler rather than relying on its default polling interval.
- Media3 1.11.0 enabled dynamic ExoPlayer work-loop scheduling by default. Its 1.10.0 release added the renderer-side duration-to-progress hint; the exact 1.11.1 DefaultRenderersFactory source documents that this hint only takes effect for asynchronous MediaCodec decoders with a registered callback and can increase CPU idle time/reduce power use.
- The custom FFmpeg SoftwareVideoRenderer is not changed: this opt-in is specifically the MediaCodec renderer capability and does not pretend to provide a next-frame deadline for the software decoder.
- Added host coverage for the API 30/31 eligibility boundary. The test was added before the helper and renderer wiring, but no local test runner was available to establish a RED/GREEN execution.

**Cycle 218 — capture actual dropped-frame and frame-processing diagnostics**
- Added source-session dropped-video-frame totals to PlaybackDiagnostics, with non-negative accumulation and saturation at Long.MAX_VALUE. The counter deliberately survives prepare-attempt changes because delayed AnalyticsListener callbacks may arrive after a retry begins.
- The Media3 analytics listener now logs dropped-frame batches with the source-session cumulative count, event playback position, callback interval and decoder name. It also logs Media3's aggregated video-frame processing offset as an average per frame, omitting invalid zero/negative frame-count samples.
- These signals make decoder/render pressure observable on real devices instead of relying only on first-frame and decoder-initialization timestamps. They are diagnostics only and do not change frame dropping, buffering, track selection or playback timing.
- Added host test source for dropped-frame accumulation across prepare attempts, saturating overflow behavior and processing-offset averaging. The tests were not executed; no Gradle test, Kotlin compilation, Android build, CI, device playback or benchmark was started.
- Exact dependency is Media3 1.11.1. Context7 checked its versioned release notes/API surface; Exa independently checked Android Developers' Media3 analytics and customization guidance and the exact AndroidX DefaultRenderersFactory implementation. The relevant sources are [Media3 1.11.1 DefaultRenderersFactory](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java), [Media3 release notes](https://github.com/androidx/media/blob/1.11.1/RELEASENOTES.md), [asynchronous buffer queueing](https://developer.android.com/media/media3/exoplayer/customization) and [Media3 analytics](https://developer.android.com/media/media3/exoplayer/analytics). Android Developers documents that async MediaCodec queueing is default from API 31 and that renderer duration-to-progress scheduling only activates for asynchronous MediaCodec callbacks.

### Verification boundary — cycles 217–218
- GitHub source was edited on an isolated feature branch based on main-build-testing at 4e0356ffdadf30bad486c436e61bc131cb0a4ba0. The API-eligibility and dropped-frame accounting tests were committed before their corresponding production wiring. The frame-offset average assertion was added after its pure helper and is not claimed as TDD-verified. Host tests were not run because this environment has no repository checkout/Gradle execution environment; no build or CI was started.
- Static self-review completed against freshly re-fetched files: the exact 1.11.1 setter and AnalyticsListener callback signatures match; the API 30/31 policy and overflow helper are covered by host-test source; dropped-frame totals remain source-session scoped; the note matches the implementation. No compiler or test runner was available, so source-level review is not compilation or test execution. No runtime, battery, thermal, frame-drop or power improvement is claimed as measured.


**Cycle 219 — make Android download resumption, deletion, export, and lock lifecycle safe**
- Resumable transfers now send a Range request only when the persisted validator is a syntactically valid strong entity tag. Legacy Last-Modified values, weak ETags, malformed tags, or missing validators cause the partial sink to be truncated and the transfer to restart from byte zero. Only strong response ETags are persisted; Last-Modified is no longer used as an unverified If-Range substitute.
- Download-store removal now requires the exact transfer generation and returns the removed record only when that generation is still current. The scheduler cancels the system job only after that guarded removal succeeds. Delayed cleanup remains serialized with transfer execution and rechecks that no replacement generation has appeared before deleting files.
- SAF export now counts bytes actually read and written and verifies that count against the source file length. It compares the destination's reported DocumentFile length only when the provider reports a positive value; AndroidX DocumentFile returns 0 when COLUMN_SIZE is unavailable, so 0 is not treated as proof of an incomplete non-empty copy.
- Replaced the scheduler's permanently growing filename-to-Mutex map with a reference-counted keyed mutex. Each caller is counted before it can wait, including cancelled waiters; entries are removed only when the reference count reaches zero and tryLock confirms the mutex is idle. The count and removal decision are serialized with ConcurrentHashMap.compute for the key.
- Evidence: RFC 9110 §13.1.5 defines If-Range and prohibits weak entity tags; an HTTP-date is permitted only when it is a strong validator. This implementation deliberately uses strong ETags only because it does not persist the response Date needed to establish a strong Last-Modified validator. The AndroidX DocumentFile source documents length() as a COLUMN_SIZE query with a default of 0 when unavailable. The exact kotlinx.coroutines 1.10.2 Mutex source confirms cancellable lock acquisition, unlock-on-cancellation after acquisition, fairness, tryLock behavior, and withLock's finally-based unlock.
- Validation boundary: no test sources were added or executed, and no Gradle task, compilation, build, CI, APK generation, or device test was started. The changes received source-level review only; runtime and regression verification remain outstanding.

**Correction to the closed-PR audit above**
- The earlier statement that strong-validator resume handling from PR #24/#28 was already present was incorrect. At baseline commit 66e1142, AndroidDownloadTransfer still fell back to Last-Modified and could issue Range without a strong validator. Cycle 219 closes that gap by requiring a strong ETag before resuming.


**Cycle 220 — preserve FFmpeg software-video colorimetry through both output paths**
- Expanded the native decoded-frame metadata contract to return FFmpeg 8.0 color_primaries, color_trc, and color_range independently from the YUV matrix classification. Android display standard is now derived from source primaries when specified; the matrix is used only as a fallback for unspecified primaries. This corrects cases where BT.2020 primaries were labelled BT.709 because the YUV matrix differed.
- Added explicit Android dataspace mappings for BT.709, BT.470M, BT.601 625/525, BT.2020, Film, DCI-P3 and the exact Display-P3/sRGB/full-range combination. Transfer mapping now distinguishes linear, sRGB, gamma 2.2, 2.6 and 2.8, SMPTE 170M-family transfer, PQ and HLG instead of collapsing all non-PQ/HLG transfers into SDR. Full versus limited range is retained.
- Both the direct ANativeWindow YV12 path and the Media3 YUV output path now receive the same per-frame dataspace. In direct-surface mode the dataspace is carried in the output buffer's otherwise-unused colorspace field while decoderPrivate continues to own the native AVFrame handle; this avoids losing color metadata when using the zero-intermediate-copy surface path.
- Added host regression coverage for matrix/primaries independence, explicit PAL/NTSC primaries, Film/DCI-P3/Display-P3, transfer-function mappings, full/limited range, unknown-metadata fallback and per-frame output-buffer ownership.
- Sources checked: FFmpeg 8.0 libavutil/pixfmt.h (the exact enum values used by JNI), Android NDK ADataSpace definitions and ANativeWindow buffer API, and the exact Media3 1.11.1 VideoDecoderOutputBuffer contract. No Media3 dependency bump was made; 1.11.1 remains the stable release, while 1.12.0-beta01 was reviewed but not adopted without a full compile/regression pass.
- Validation boundary: regression tests were added but not executed. No Kotlin compilation, native C/C++ compilation, Gradle task, build, CI, APK generation or device playback run was started. This is source-reviewed only; actual device colour output still needs verification on SDR, HDR10/PQ and HLG material.

**Cycle 221 — avoid duplicate native seek-preview work for exact cached frames**
- Native thumbnail scrubbing now reuses an exact-position cached frame without issuing another feed decode request. Any delayed, not-yet-issued request is cancelled when the exact cached frame is already available. Nearby cached frames still act as visual placeholders but do not suppress a request for the exact requested position.
- Background warm-up now checks for an exact cached anchor frame before requesting another decode. When a prior feed frame is already available, it reuses that frame and continues the normal prefetch schedule rather than restarting decode work.
- Added host regression coverage for exact cached positions, nearby-but-not-exact positions, and cache misses.
- Validation boundary: tests were added but not run; no build or CI was started. The expected benefit is fewer redundant thumbnail decode/feed requests during repeated scrubbing; no frame-time or network reduction is claimed as measured.


**Cycle 222 — keep seek-preview scrubbing requests latest-wins**
- The Android native thumbnail adapter previously submitted every scrub position to a single-thread executor's unbounded work queue. When native thumbnail work took longer than the scrub event cadence, obsolete positions could accumulate ahead of the current thumb position.
- Replaced per-position executor submissions with a single atomic latest-request slot and one scheduled drain. A burst of positions now retains only the newest pending target while a native request is in flight; the drain consumes the latest request and loops only if another target arrived. The close path clears pending work, and late requests are ignored once closed.
- Added host regression coverage for latest-position retention, empty-after-take behavior and clearing pending work during close.
- This bounds queued scrub work to one pending target plus the currently executing native call. It is a queue/backlog guarantee, not a measured thumbnail latency or frame-rate improvement.

**Cycle 223 — preserve the complete seek-preview frame across orientations**
- The ExoPlayer preview grabber previously used `Presentation.createForHeight` with a height derived from a 16:9 assumption. Portrait sources therefore received a very narrow decode target, and the preview bubble then used `ContentScale.Crop`, cutting off substantial parts of portrait frames and ultrawide edges.
- Switched the Media3 1.11.1 preview effect to `Presentation.createForShortSide`, which sizes from the shorter side regardless of orientation. The overlay now uses `ContentScale.Fit` so the complete decoded frame remains visible inside its existing bounded preview bubble; black letterboxing is preferred over silently cropping source content. The bubble's existing 1.0–2.4 aspect-ratio bounds remain unchanged.
- Extracted the short-side sizing and display-aspect-ratio calculations into pure helpers and added host test source for landscape scaling, portrait bounds, ultrawide bounds and invalid dimensions.
- Context7 confirmed `Presentation.createForShortSide` is present in Media3 1.11.1 (introduced in 1.8.0). This does not change the player renderer or the main playback output path.



**Cycle 224 — skip GPU readback when no seek-preview request is waiting**
- The ExoPlayer preview effect previously performed `glReadPixels`, allocated an ARGB bitmap and copied the pixel buffer for every frame reaching the reader, even after the pending frame future had been cancelled or the grabber had been released.
- Added a request/release guard before the GPU readback. When no active waiter exists, the shader now immediately calls `onInputFrameProcessed` to return the input texture to the effect pipeline without transferring pixels to CPU memory or allocating a bitmap. Active requests retain the existing readback and delivery path.
- Added host test source for active, cancelled/no-waiter and released states.
- Expected effect: avoid unnecessary GPU-to-CPU readback and bitmap allocation for stale/cancelled preview frames. No measured GPU time, memory allocation or scrub latency improvement is claimed.

**Verification boundary — cycles 222–224**
- Source changes and regression-test source were committed on `feature/seek-preview-quality-responsiveness`; the feature branch is intended for fast-forward integration into `main-build-testing` only after remote source review.
- No Gradle test task, Kotlin compilation, Android build, CI, APK generation or device playback was run, in accordance with the no-build/no-CI instruction. Tests are added but unexecuted; this is source-level review, not a passing test or visual-device validation.
- The queue design was reviewed for offer/take/close races, including the handoff between clearing the scheduled flag and rescheduling a newly arrived request. Device-level scrubbing responsiveness still needs measurement under slow native thumbnail extraction.
- The portrait/ultrawide framing change is grounded in the Media3 1.11.1 Presentation API, but preview appearance still needs visual verification on real portrait, 16:9 and ultrawide samples. The preview should not be described as device-verified.
- The cancelled-frame readback guard preserves the GL effect's input-release callback even when pixel extraction is skipped. Its test source was added but not executed; actual allocation and latency savings have not been profiled.


**Cycle 225 — use explicit AVC bit depth when deciding on the software fallback**
- `SoftwareVideoDecoder.needsSoftwareDecoding` previously depended on the AVC profile ID (High 10, High 4:2:2, High 4:4:4 and CAVLC 4:4:4). If the container omitted the codec string but supplied explicit Media3 `ColorInfo.lumaBitdepth` or `chromaBitdepth`, the software renderer could incorrectly report the stream as outside its handled formats.
- AVC selection now also recognises explicit luma or chroma bit depth above 8. This is only a software-renderer capability signal: the normal hardware renderer retains its existing priority, so hardware-capable devices are not forced onto FFmpeg by this change.
- Added host-test source for 10-bit AVC with no codec string and for ordinary 8-bit AVC remaining on the existing path.

**Cycle 226 — recover AVC/HEVC profile detection from later codec entries and AVC SPS**
- The previous profile parsers stopped at the first matching codec prefix even when that token was malformed. AVC's SPS fallback was also skipped whenever any `avc1.`/`avc3.` token existed, even if its profile field could not be parsed.
- AVC and HEVC parsers now inspect every matching comma-separated codec entry rather than stopping at the first valid profile, so a later high-bit-depth/range-extension declaration cannot be hidden by an earlier lower profile. AVC also scans every `Format.initializationData` entry for a usable Annex-B/raw SPS profile, even when a codec-string profile is present, so actual SPS evidence is not hidden by a stale container hint.
- This improves fallback selection when codec metadata is incomplete or malformed but the stream's own AVC SPS still identifies a high-bit-depth profile. No changes were made to Media3's general codec-string parser or to manifest track selection.
- Added host-test source for malformed-first and mixed-profile AVC/HEVC codec lists, AVC SPS fallback after malformed or conflicting codec hints, and SPS discovery in a later initialization-data entry.

**Cycle 227 — add a 12-bit HEVC fallback without displacing Main10 hardware decoding**
- HEVC Range Extensions profile 4 already selected the software renderer. Explicit 12-bit `ColorInfo` now does too, covering streams whose codec string is missing or does not identify the range-extension profile.
- The threshold is deliberately above 10 bits: ordinary HEVC Main10 remains on the hardware-first path. The software decoder still converts to its existing 8-bit YV12 output, so this is a compatibility fallback rather than a claim of 12-bit output preservation.
- Added a diagnostic reason for each software-decoder selection (AVC profile, AVC bit-depth metadata, HEVC Range Extensions, 12-bit HEVC, VC-1, or generic capability fallback) and log the reported luma/chroma bit depths when the decoder is created.
- Added host-test source for 12-bit HEVC fallback, Main10 remaining on the hardware path, and the new reason labels.

**Verification boundary — cycles 225–227**
- Media3 remains pinned to the existing 1.11.1 version; no dependency or native ABI changes were made. The exact tagged Media3 1.11.1 `Format.java` and `ColorInfo.java` sources confirm the initialization-data and bit-depth fields used here. Context7 did not return a direct `ColorInfo` API snippet for this query, so the implementation was checked against those official version-tagged source files.
- The new regression-test source is present but was not executed. No Gradle tasks, Kotlin compilation, native compilation, Android builds, CI, APK generation or device playback were started, as instructed.
- The 10-bit AVC and 12-bit HEVC paths still need device verification with real streams. The software output path remains 8-bit YV12 and can lose precision relative to a capable high-bit-depth hardware decoder; the default renderer ordering keeps hardware first.
- Investigated the current Dolby Vision Profile 7 fallback issue and the open HEVC compatibility-flags report in Media3 1.11.1. No beta upgrade or custom manifest rewrite was introduced: the former requires careful device capability handling through the project's patched ExoPlayer AAR, and the latter remains an open upstream report without a confirmed app-level parser hook in the current pipeline.


**Cycle 228 — recover Media3 stuck-player no-progress failures**
- Android ExoPlayer now classifies Media3 1.11.1 stuck-player exceptions for buffering-not-loading, buffering-no-progress and playing-no-progress as a bounded same-source reopen at the current playhead, using the existing one-reopen-per-ready-session recovery mechanism.
- Playback suppression and playing-not-ending are excluded to avoid network reloads for focus/lifecycle/policy states or a playhead already beyond declared duration. Existing Media3 default detection timeouts are retained.
- Added common and Android host test source for safe stuck recovery, suppression exclusion, one-shot recovery and unknown-duration rejection.

**Cycle 229 — bind first-load diagnostics to the actual media item**
- Each ExoPlayer prepare attempt now records its target media identity using mediaId, falling back to URI. Media3 load callbacks resolve the event-specific timeline/window identity and are counted only when they match the prepared item.
- This closes the stale/prebuffered-next-item ambiguity in the previous elapsed-time-only correlation. Missing event identity is rejected when the attempt has a known target; identity is not logged.
- Added host test source for matching, mismatched and absent identities.

**Verification boundary — cycles 228–229**
- Confirmed the branch dependency is Media3 1.11.1 and checked the versioned StuckPlayerException and AnalyticsListener.EventTime APIs through Context7 and AndroidX source.
- Regression-test source was committed before production changes. Tests were not executed; no Gradle task, compilation, build, CI, APK/AAB or device playback was run because no build/CI authorisation was given. Static source inspection does not establish compilation or runtime correctness.


**Cycle 230 — verify current Media3 video-seek and scheduling defaults**
- The exact Media3 1.11.1 DefaultRenderersFactory source already defaults AV1 sample-dependency parsing to enabled; no redundant setter was added.
- The 15 ms late decoder-input drop threshold is already the Media3 default. Dynamic player scheduling is enabled by Media3 1.11.0, and this app already enables the renderer duration-to-progress hint on API 31+.
- Renderer prewarming was not enabled: the app replaces one media item per prepare and its custom renderer factory does not provide the matching secondary video renderer required by Media3 when buildVideoRenderers is overridden.
- This is a deliberate no-op after exact-version source inspection, not a claimed code improvement or measured performance gain.


### Cycle 231 — bidirectional decoder recovery without losing track choices

- The Android renderer factory inserts the custom software video renderer before platform renderers in PREFER mode and after platform renderers in ON mode. The prior recovery branch only handled a configured ON preference, so a failure while the app renderer was preferred could leave the user stuck even though the platform decoder was still available.
- Decoder failures now trigger at most one opposite-priority player rebuild for either configured ON or PREFER: ON retries with app renderers preferred; PREFER retries with platform renderers preferred. OFF remains respected and is never overridden. The active priority must still equal the configured priority, which prevents a second fallback loop after the first retry.
- Before rebuilding, the current audio track selection and text-track ordinal are captured. A selected subtitle track is restored after the replacement player discovers text tracks; a stored -1 also restores the user's explicit subtitles-disabled state when text tracks were available.
- Pending audio/subtitle restoration is now keyed to the source identity: same-source renderer rebuilds retain the pending selection, while switching to a different source clears it. Subtitle restoration no longer consumes a positive track index on an earlier video/audio-only track update before text tracks have appeared.
- Added host-test contracts for both decoder-priority directions, one-shot behavior, respecting OFF, non-decoder errors, and waiting for text tracks. Test source was committed before production changes. Tests were not executed because no build/test task was authorised.

#### Versioned source evidence

- [Media3 1.11.1 DefaultRenderersFactory](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java) documents that ON places extension renderers after core renderers, while PREFER places them before core renderers.
- [Media3 1.11.1 ExoPlaybackException](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlaybackException.java) distinguishes renderer failures from source failures and documents its internal recoverable-renderer state. ExoPlayer performs its own disable/re-enable recovery for errors marked recoverable; this app-side opposite-priority rebuild remains limited to terminal decoder failures delivered to onPlayerError.
- The custom software renderer's insertion policy was verified in SubtitleOffsetRenderersFactory.buildVideoRenderers and softwareVideoRendererInsertionIndex; no assumption about generic Media3 extension ordering was substituted for the app's actual code.

#### Verification boundary

- Final diff review caught and corrected the AndroidView constructor named argument to match `NuvioLibmpvView.initialRenderQuality`; this was found by source inspection, not compilation.

- Static review checked the new helper call sites, source-key lifetime, capture-before-rebuild ordering, text-track gating and the existing one-retry guard.
- No Gradle task, test execution, compilation, build, CI, APK/AAB, Macrobenchmark, Perfetto capture or physical-device playback was run. No measured playback or decoder-recovery improvement is claimed.


### Cycle 232 — preserve speed and audio choice through video-renderer rebuilds

- The same-source player is rebuilt when the ASS video-effect renderer is activated or anime video effects are changed. These rebuilds already preserved the playhead and some subtitle state, but they did not consistently preserve the selected audio track or non-default playback speed.
- The ASS-triggered video-effect rebuild now captures the selected audio track before replacing the player. It already preserves the selected ASS subtitle ordinal; the previous player replacement therefore no longer silently resets the audio choice.
- Decoder fallback, anime-effect rebuild and ASS video-effect rebuild now capture the current non-default playback speed before the player is replaced. The replacement player reapplies that speed once, then clears the pending value so later player instances do not inherit stale speed state. Normal 1.0x playback remains on Media3's default path.
- Added host-test coverage for valid non-default speeds and rejection of 1.0x, zero, NaN and infinite values. Test source was committed before implementation; tests were not executed.
- This is a continuity/correctness improvement across existing player rebuilds, not a claim of higher decoder throughput or measured frame-rate improvement.

#### Verification boundary

- Static source inspection checked every existing renderer/player rebuild trigger found in this file, capture-before-key-change ordering, and one-shot speed restoration.
- No Gradle task, test execution, compilation, build, CI, APK/AAB, Macrobenchmark, Perfetto capture or physical-device playback was run. No measured performance gain is claimed.


### Cycle 233 — distinguish disabled subtitles from automatic no-selection

- The review of the new rebuild-preservation path found an important edge case: Media3 can report no selected text track both when the user explicitly disabled subtitles and when automatic selection simply has not selected a compatible text track. Treating every -1 ordinal as an explicit disable could suppress subtitles after a rebuild.
- Subtitle rebuild state now preserves a positive selected ordinal, or preserves -1 only when the text track type is explicitly present in TrackSelectionParameters.disabledTrackTypes. A missing automatic selection is left to Media3's normal track-selection policy.
- Added host-test cases for selected text tracks, explicit disable, automatic no-selection and sources without text tracks. Test source was committed before the implementation correction; tests were not executed.
- This narrows the previous cycle's subtitle-state preservation to the exact state the user had chosen, without overriding automatic subtitle selection.

#### Versioned source evidence

- Media3 1.11.1 TrackSelectionParameters exposes disabledTrackTypes as the authoritative set of explicitly disabled track types; this is used instead of inferring explicit user intent from selectedTextTrackOrdinal() alone.

#### Verification boundary

- Static inspection checked both decoder-fallback and anime-effect rebuild capture sites and confirmed the helper reads the current player parameters before the replacement player is created.
- No Gradle task, test execution, compilation, build, CI, APK/AAB or device playback was run.


### Cycle 234 — remove the forced low-quality MPV renderer default

- The Android libmpv view previously ran `apply-profile fast` unconditionally. The current upstream mpv `fast` profile explicitly changes upscaling and downscaling to bilinear, disables dithering, disables corrected and linear downscaling, disables sigmoid upscaling and disables automatic HDR peak computation.
- mpv's own GPU output documentation says its unprofiled defaults are balanced for quality and performance; `fast` is for maximum performance and `high-quality` is a separate opt-in. New installs and users without a saved quality choice now use the native balanced defaults instead of being forced into `fast`.
- This restores mpv's normal Lanczos-based scaling and automatic dithering/downscaling behavior without forcing an expensive EWA scaler on every Android device.

### Cycle 235 — selectable and synchronized MPV render-quality modes

- Added three Android libmpv render choices: Balanced (mpv native defaults), High quality (upstream `high-quality` profile), and Performance (upstream `fast` profile).
- High quality is deliberately opt-in. The upstream EWA Lanczos sharp filter can be demanding on mobile GPUs and community reports document aliasing on some Android devices; the user can compare it on their hardware or return to Balanced.
- The setting is profile-scoped, persisted on Android and represented in the existing settings-sync payload. iOS storage implements the shared storage contract, but the Android-only setting is not shown in iOS UI.
- The selection is passed through the existing Android libmpv surface, so changing the setting while MPV is open applies it to the running renderer rather than waiting for the next app launch.

### Cycle 236 — reversible live profile changes and HDR/display-path audit

- mpv runtime profiles are destructive: applying a second profile overwrites only the options that second profile contains, leaving unrelated values from the first profile behind. The MPV view now sets `profile-restore=copy`, restores the currently active profile, then applies the next profile. Returning to Balanced therefore restores mpv's original values rather than leaving a partial Performance/High-quality mix.
- Added common tests for preset-to-profile mapping, Balanced defaults, no-op same-profile selection and restore-before-apply transitions. Added Android host tests for persisted selection and settings-sync round trips. These test sources were committed before the production implementation where applicable; none were executed because no build/test task was authorized.
- Audited Media3 1.11.1 `PlayerView`: its default surface is `SurfaceView`, and its documentation recommends `surface_view` for video applications. The current ExoPlayer path therefore retains the native SurfaceView path; no TextureView conversion was made.
- Community investigation found Findroid reports of HDR color-shift/banding and the difference between MPV and ExoPlayer output. Stock Android MPV HDR passthrough remains a limitation in the cited project discussion; the app's ExoPlayer path remains the safer HDR-display choice. The MPV quality preset is not presented as an HDR passthrough fix.
- Did not force `video-sync=display-resample` or `display-fps-override`: mpv documents that `container-fps` may be bogus and `estimated-vf-fps` is a rolling 10-frame estimate that can be unstable. Android community reports also show display-resample timing issues on some devices. This needs device-specific frame-pacing validation before becoming a default.
- Reviewed the newer `mpv-android-lib 0.2.2` source delta over 0.2.1: it adds Vulkan 1.1 function lookup compatibility for Android below API 28 and adds `estimated-frame-number` to MPV tick notifications. The dependency was not upgraded in this pass because it changes native binaries and requires authorized build/device validation.

#### External source review

- [mpv current built-in profiles](https://github.com/mpv-player/mpv/blob/master/etc/builtin.conf) and [GPU output documentation](https://mpv.io/manual/master/#video-output-drivers-gpu): confirms the exact `fast` and `high-quality` profile roles and balanced native defaults.
- [mpv scaler discussion #12082](https://github.com/mpv-player/mpv/issues/12082): developers explain the quality/performance tradeoffs between Lanczos, spline36 and EWA Lanczos, including ringing and aliasing.
- [mpv-android EWA aliasing issue #560](https://github.com/mpv-android/mpv-android/issues/560): Android users reported aliasing and GPU intensity with EWA Lanczos on mobile hardware.
- [Findroid HDR issue #645](https://github.com/jarnedemeulemeester/findroid/issues/645) and [high-quality profile PR #652](https://github.com/jarnedemeulemeester/findroid/pull/652): community reports motivated the selectable profile and reinforce that MPV HDR output and scaling quality are separate concerns.
- [Media3 1.11.1 PlayerView source](https://github.com/androidx/media/blob/1.11.1/libraries/ui/src/main/java/androidx/media3/ui/PlayerView.java): documents SurfaceView as the default and recommended video surface.
- [mpv-android-lib 0.2.1 to 0.2.2 diff](https://github.com/abdallahmehiz/mpv-android/compare/v0.2.1...v0.2.2.diff): exact upstream native patch set reviewed but not adopted without build verification.

#### Verification boundary

- Re-fetched all changed files from `main-build-testing`; static checks cover default Balanced selection, preset mappings, profile restoration order, Android/iOS storage contract, sync serialization, UI exposure and live MPV application.
- No Gradle task, test execution, compilation, build, CI, APK/AAB, benchmark, Perfetto trace or physical-device playback was run. No measured image-quality, frame-rate or battery improvement is claimed.


### Cycle 237 — optional live debanding for gradient banding

- Added an Android libmpv Debanding switch, disabled by default. It changes mpv's GPU `deband` option live through the documented `options/deband` runtime property; no playback restart is required.
- The setting is profile-scoped, persisted on Android, implemented in the iOS storage actual for the shared contract, and included in Android settings sync.
- Kept debanding separate from the High-quality profile. Upstream removed debanding from that profile because one fixed strength cannot improve already-clean material without risking detail loss; the community discussion recommends enabling it only when needed. The UI warns that hardware-decoder paths may limit the effect and that GPU processing can soften fine detail.
- Updated renderer descriptions to explain the Android HDR distinction: the stock libmpv path tone-maps HDR to SDR, while ExoPlayer's Media3 PlayerView retains its recommended SurfaceView output for native HDR when the device/display supports it. This avoids implying that a sharper scaler enables HDR passthrough.
- Added common and Android host test source for the disabled default, persistence, and settings-sync round trip. Tests were not executed.

#### Additional source review
- The same 2026 Findroid discussion also reviews an experimental HDR-capable Vulkan MPV fork and reports that it cannot use MediaCodec-copy/HW+ and may stutter on large high-bitrate files; this is not a safe default engine switch without device testing.

- [mpv debanding option and runtime properties](https://github.com/mpv-player/mpv/blob/master/DOCS/man/options.rst) and [libmpv option property API](https://github.com/mpv-player/mpv/blob/master/DOCS/man/input.rst#property-list): confirms the option and runtime `options/<name>` setter.
- [mpv PR #13731](https://github.com/mpv-player/mpv/pull/13731): upstream discussion explains why debanding is not enabled in default quality profiles and why excessive debanding can blur details.
- [mpv hardware-decoding quality notes](https://github.com/mpv-player/mpv/blob/master/DOCS/man/options.rst): documents that some hardware-decoding output paths force RGB conversion and may prevent filters such as debanding from operating ideally.
- [mpv-android issue #1137](https://github.com/mpv-android/mpv-android/issues/1137): a real Android TV report attributes dropped output frames to the GPU-next rendering path despite zero decoder drops; this reinforces keeping expensive processing opt-in.
- [Emby Android TV discussion](https://emby.media/community/topic/76912-mpv-on-android-tv/): community experience highlights that refresh-rate matching can improve cadence, but this older thread does not establish a safe universal Android implementation.

#### Verification boundary

- Static review checked the deband option is initialized before playback, applied through the runtime option property on changes, and guarded against redundant writes. Renderer descriptions and HDR claims were rechecked against the cited sources.
- No Gradle task, test execution, compilation, build, CI, benchmark, Perfetto trace or physical-device playback was run. No measured improvement in banding, sharpness, frame pacing or GPU cost is claimed.


### Cycle 238 — keep the independent deband preference stable across profile changes

- Render profiles and the Debanding toggle are independent controls. The renderer now reapplies the user's current deband value after every profile transition, so a future upstream profile that starts modifying `deband` cannot silently override the explicit user choice.
- This preserves the deband toggle whether the user changes quality first, debanding first, or switches repeatedly between profiles. The command remains on the existing serialized MPV dispatcher.
- Static review verified the reapplication occurs after restore/apply commands and uses the latest requested value; no build or test task was run.


### Cycle 239 — preserve exact Media3 subtitle cue geometry

- Replaced the lossy left/centre/right and top/middle/bottom-only mapping for supported horizontal Media3 text cues with normalized `position`, `positionAnchor`, fractional `line`, `lineAnchor`, explicit `size` and independent `textAlignment`.
- The custom commonMain `Layout` measures each cue before applying its anchor. Explicit cue sizes are bounded by the available space around the anchor. When size is unset, the text is measured to its actual content width and wrapping is limited to the available region; it is not stretched to a full-width cue box.
- Media3's unset anchor behavior is START, as implemented in Media3 1.11.1 `SubtitlePainter`; missing text alignment remains CENTER. Non-finite/out-of-range coordinates are ignored.
- Different geometry and text alignment values remain separate blocks. Bitmap, vertical-writing and numbered-line cues are routed to Media3's native subtitle output instead of being incorrectly drawn by the horizontal custom overlay.

### Cycle 240 — preserve embedded subtitle window colors

- The presenter now carries `Cue.windowColor` only when `windowColorSet` is true. A supplied window color overrides the user's subtitle background for that cue; an unset window color continues to use the configured user background.
- For an explicitly sized cue, the custom background fills the cue box; for an unset size, it remains sized to the measured text box. Window color is part of the grouping key so cues with different colors are not merged.

### Cycle 241 — preserve embedded cue font sizes

- The presenter carries Media3's `Cue.textSize` and `textSizeType` through the common model.
- Absolute values are treated as pixels; fractional values use the fitted video-frame height minus padding; fractional-ignore-padding values use the raw fitted video-frame height. The full-bleed overlay has no additional padding, so both fractional inputs resolve to the fitted frame height.
- Resolved pixel sizes are converted through Compose `Density.toSp()`, preserving the intended rendered pixel size while leaving the user's configured font size as the fallback when the cue supplies no embedded size.

### Cycle 242 — anchor subtitle coordinates to the fitted video frame

- `SubtitleVideoBoundsPx` now includes `left` and `top` offsets as well as width/height. Fit mode records positive letterbox offsets; Zoom records negative crop offsets; Fill remains the full container.
- Subtitle positions, widths, default bottom/top insets, font scaling, outline sizing and fractional embedded text sizes are now based on the fitted video bounds rather than the outer player container.
- The custom overlay clips to the player container, so Zoom-cropped video coordinates cannot draw outside the player. Default bottom-centre cues remain inside the actual video frame when Fit introduces letterboxing.
- Added common test source for Fit/Fill/Zoom offsets, fractional anchor placement, bounded cue widths, invalid metadata, native fallbacks, embedded window colors and all supported text-size modes.

#### Research and source checks

- [Media3 1.11.1 Cue source](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/text/Cue.java): source fields, anchors, cue size, window color and text-size types.
- [Media3 1.11.1 SubtitlePainter](https://github.com/androidx/media/blob/1.11.1/libraries/ui/src/main/java/androidx/media3/ui/SubtitlePainter.java): exact position/line anchor behavior, cue-box sizing and text alignment.
- [Media3 1.11.1 CanvasSubtitleOutput](https://github.com/androidx/media/blob/1.11.1/libraries/ui/src/main/java/androidx/media3/ui/CanvasSubtitleOutput.java) and [SubtitleViewUtils](https://github.com/androidx/media/blob/1.11.1/libraries/ui/src/main/java/androidx/media3/ui/SubtitleViewUtils.java): distinction between raw view height and height after padding for cue text size.
- [Media3 PlayerView layout](https://github.com/androidx/media/blob/1.11.1/libraries/ui/src/main/res/layout/exo_player_view.xml) places `SubtitleView` inside `exo_content_frame` (`AspectRatioFrameLayout`). [AspectRatioFrameLayout.onMeasure](https://github.com/androidx/media/blob/1.11.1/libraries/ui/src/main/java/androidx/media3/ui/AspectRatioFrameLayout.java) applies the same Fit/Zoom sizing and offsets mirrored by `subtitleVideoBoundsPx`.
- [AndroidX Media issue #2977](https://github.com/androidx/media/issues/2977#issuecomment-3705708252) is a TV-oriented request to render subtitles outside the video bounds into black bars. The maintainer explains that subtitle formats commonly position content relative to the video and that PlayerView behaves accordingly. The outside-video behavior was deliberately not implemented because this project targets phones/tablets and avoids TV-specific scope.
- [ExoPlayer issue #7243](https://github.com/google/ExoPlayer/issues/7243): maintainer discussion of WebVTT position, anchor, size and alignment interactions.
- [Stack Overflow discussion on custom ExoPlayer subtitle positioning](https://stackoverflow.com/questions/68802859/how-to-change-exoplayer-subtitle-position-in-android): older community workaround considered as context only; implementation follows current Media3 source instead.
- Exa searches of Reddit and current forum discussions did not surface a stronger reproducible implementation. Current Media3 source and maintainer issue discussion were treated as the source of truth.
- Context7 was queried for the project's Compose Multiplatform 1.13.0-beta01 Layout/Density APIs but did not return version-matched documentation. The stable common Compose API signatures were cross-checked against official JetBrains/Compose API sources; no dependency upgrade was made.

#### Verification boundary

- Static remote-source review checked Cue mapping, anchor defaults, width bounds, grouping keys, embedded window-color/text-size handling, Fit/Fill/Zoom offsets, video-frame-relative layout and native fallbacks.
- Common and Android host regression tests were added but **not executed**. No Gradle task, compilation, build, CI, benchmark, Perfetto trace or physical-device playback was run. No runtime improvement is claimed until authorized validation is performed.

## Media3 1.12.0-beta01 — HDR metadata, Dolby Vision fallback and buffer memory (23 November 2026)

### Integrated on `main-build-testing`

- Updated the single `androidx-media3` version-catalog alias from `1.11.1` to `1.12.0-beta01`; all catalog-managed Media3 modules continue to share that alias.
- The project already declares `android-minSdk = "24"`, matching Media3 1.12's new minimum SDK. No minimum-SDK increase was made.
- Media3 1.12 beta parses HLS `VIDEO-RANGE=PQ` and `VIDEO-RANGE=HLG` from `EXT-X-STREAM-INF` and `EXT-X-I-FRAME-STREAM-INF` into `Format.colorInfo` (upstream change #3441). This gives the existing Media3 track-selection/rendering path actual dynamic-range metadata instead of relying on an absent field.
- Media3 1.12 beta allows HEVC fallback for backward-compatible Dolby Vision Profile 7 streams and fixes decoder selection so a Dolby Vision decoder for the wrong profile is not selected as a false-positive primary decoder (upstream change #3486).
- Media3 1.12 beta reduces `C.DEFAULT_BUFFER_SEGMENT_SIZE` from 65,536 to 65,520 bytes so each allocation including the array header fits whole OS pages. Google's release notes report about 6% lower sample-buffer memory on 4 KiB-page devices and 20% on 16 KiB-page devices; these are upstream estimates, not Nuvio measurements.
- Added `scripts/tests/test-media3-112-beta-compatibility.sh`. It checks the exact shared version, minSdk >= 24, catalog module alignment, and known removed API references (`DashUtil.buildDataSpec`, `setAsyncCryptoSynchronizationEnabled`, `TextureAssetLoader`).

### Compatibility and risk boundaries

- The beta enables load-only-selected-tracks by default for progressive sources, filtering unselected video/image tracks. This can reduce unnecessary sample loading, but a later switch to another progressive video track may require additional loading/buffering. This behavior must be tested with multi-video-track MP4/MKV samples before production release.
- Existing HLS Auto quality remains Media3-adaptive; no custom variant rewriting or quality policy was changed.
- Existing custom `DefaultRenderersFactory` video/audio/text renderer overrides remain in place. No MediaCodec renderer prewarming was enabled because Nuvio supplies a custom video renderer but no matching secondary renderer.
- HAGC metadata parsing remains enabled. The 1.11.1 `DefaultExtractorsFactory` already defaults `setParseHagcMetadata` to true, so no redundant flag was added.
- The beta minimum SDK is compatible with the current project minSdk 24. The removed-API repository searches returned no references; the committed guard now protects these known removals.

### Evidence and verification boundary

- Official release notes: https://github.com/androidx/media/releases/tag/1.12.0-beta01
- HLS HDR metadata parsing: https://github.com/androidx/media/issues/3441
- Dolby Vision Profile 7 and profile-aware decoder selection: https://github.com/androidx/media/issues/3486
- Media3 1.12 AndroidX release page: https://developer.android.com/jetpack/androidx/releases/media3
- Context7 resolved `/androidx/media` only through stable 1.11.1; the beta was checked against the official 1.12.0-beta01 release notes and upstream source instead of claiming Context7 beta documentation.
- The compatibility guard was exercised against positive and negative fixture cases: valid catalog passed; wrong module version, minSdk 23 and removed-API references each failed as intended. The repository script itself was not run against a local checkout.
- GitHub source searches found no references to the known removed APIs, and the live version catalog confirms minSdk 24 and all Media3 artifacts use the shared alias.
- No Gradle tasks, tests, compilation, build, CI, APK/AAB, benchmark, Perfetto capture or physical-device playback was run. This is a testing-branch beta adoption, not a production-ready validation claim. Before release, run Android builds/tests and validate HLS SDR/PQ/HLG, Dolby Vision Profile 7, unsupported profiles, progressive multi-track switching, subtitle continuity and software-decoder fallback on representative API 24+ devices.

## HLS quality chooser — dynamic-range and frame-rate visibility (23 November 2026)

### Integrated on `main-build-testing`

- Extended the common `PlayerQualityVariant` model with optional `videoRange` and `frameRate` metadata, preserving existing constructor call sites with null defaults.
- The custom HLS master resolver now retains `VIDEO-RANGE` (PQ, HLG or SDR) and parses only finite positive `FRAME-RATE` values from `#EXT-X-STREAM-INF`.
- The existing quality chooser subtitle now includes `HDR (PQ)`, `HDR (HLG)` or `SDR`, plus a concise frame-rate label such as `23.98 fps`, alongside codec and bandwidth. Unknown ranges and invalid/missing frame rates are omitted.
- No panel layout, Auto behavior, manual playback URL, recommendation, renderer, decoder or adaptive track-selection behavior was changed. The sanitized playlist continues to preserve the original stream-info line for Media3.

### Research and decision boundary

- AndroidX Media issue #3441 documents a reproducible equal-bandwidth HLS PQ/SDR selection failure.
- AndroidX Media PR #3461 describes the more complete fix: rank HDR only when the default display supports its HDR type, keep SDR/PQ/HLG/Dolby Vision out of the same adaptive group, and reselect when display HDR capabilities change. It was not included in the 1.12.0-beta01 release notes at the time of this change. Do not treat the new labels as an HDR selection or rendering fix.
- Jellyfin Android TV issue #5245 contains a real-device report where an SDR fallback was selected instead of a PQ remux on initial playback. The issue and maintainer discussion are more reproducible and technically specific than the Reddit results returned in this search; no stronger independent Reddit implementation was found.
- Context7 resolved AndroidX Media but only exposed versioned docs through 1.11.1; the 1.12.0-beta01 release behavior was cross-checked against Google's official release notes and upstream issue/PR sources. The implementation itself uses no new Media3 API.

### Verification boundary

- Added common tests for PQ/HLG/SDR labels, unknown values, finite-positive frame-rate parsing, fractional formatting and unchanged labels when metadata is absent.
- Tests were authored before the production-code update, but the RED/GREEN execution cycle was not run because Gradle test/build execution has not been authorized. No compilation, Gradle tests, build, CI, benchmark or physical-device playback was run. No runtime or HDR output improvement is claimed.


## Playback format fidelity and diagnostics — Dolby Vision, HLS and source color details (23 November 2026)

### Integrated on `main-build-testing`

- Corrected ExoPlayer playback-info and track-badge Dolby Vision detection to recognize `dvhe`, `dvh1`, `dvav`, `dva1` and `dav1` codec strings even when the selected track exposes a fallback HEVC/AVC/AV1 MIME type. Dolby Vision profile extraction now covers the same codec families. PQ badges now say `HDR (PQ)` rather than claiming HDR10 without proof; Dolby Vision + PQ base-layer signals remain separately visible.
- Corrected libmpv dynamic-range classification to use explicit Dolby Vision profile/codec/decoder or `video-params/colormatrix=dolbyvision`, and PQ/HLG/SDR transfer information. BT.2020 primaries alone no longer imply HDR; a wide-gamut SDR source must not be labelled HDR.
- ExoPlayer and libmpv playback-info JSON now include source bit depth and normalized source color range when the active engine exposes them. The shared Playback Info modal shows these fields and omits unavailable values.
- The selected/recommended HLS quality details now show the variant's declared dynamic range and frame rate. Auto mode labels these as recommended metadata, not the currently adaptive-selected rendition.
- Extracted and tested HLS metadata normalization. URI rewriting now uses a replacement lambda so dollar-sign tokens in resolved URLs remain literal instead of being interpreted as regex replacement groups.

### Audit corrections and research

- Today's earlier HLS resolver history briefly contained a double-escaped quote-regex edit in commit `654543b`; the subsequent `fc8ddb4` correction restored the original correct quoted-URI pattern. The current branch was re-fetched and verified to use the corrected pattern. The new regression test covers replacement values containing `$1` and `$token`.
- The previous HLS metadata test set covered display helpers but not parser metadata extraction. This cycle adds direct tests for the extracted parser helper and URI rewrite path.
- Media3 PR #3461 remains open. It is the upstream solution for display-HDR-capability ranking, dynamic-range-separated adaptive selections and re-selection after display changes; Nuvio does not partially reimplement it. No custom HDR selection or display lifecycle logic was added.
- The Dolby Vision codec families were cross-checked against Nuvio's existing `Format.hdrFormats()` / `exoVideoCodec()` handling. The mpv metadata mapping follows the official property names `video-params/pixelformat`, `video-params/colorlevels`, `video-params/colormatrix` and `video-params/gamma`.
- Community/source review included maintained [Just Player](https://github.com/moneytoo/Player), AndroidX Media PR #3461, mpv discussion #14024, and Jellyfin's reported Realtek Dolby Vision decoder teardown case #5908. Nuvio already keys its ExoPlayer instance by source URL, so the reported same-instance next-item failure did not justify a duplicate workaround here.

### Verification boundary

- Added four common HLS resolver metadata tests, three common `PlayerMediaInfo` tests and five Android host playback-format tests (including all five Dolby Vision codec families, transfer-function classification, BT.2020-only SDR, color range, bit depth and consistent PQ/DV badges).
- Source files, test sources, the Media3 catalog and compatibility guard were re-fetched and reviewed. No Gradle test, compilation, build, CI, benchmark or device playback was run because authorization has not been given. No runtime playback improvement is claimed until those checks and representative device tests pass.


### Follow-up — libmpv source/post-filter format fidelity and FPS diagnostics (23 November 2026)

The earlier format-fidelity cycle exposed source bit depth and color range, but a further review of the libmpv JSON builder found that its `pixelFormat` came from `video-out-params` (after video filters) while `bitDepth` came from `video-params` (decoded/source). The shared modal labelled these simply Pixel Format and Source Bit Depth, which could make a deliberate 10-bit-to-`yuv420p` filter conversion look like 10-bit output. It also presented mpv's `container-fps` as an unqualified Frame Rate.

Changed:
- Expanded `mpvBitDepth` to recognize common 8-, 9-, 10-, 12-, 14- and 16-bit formats, including P010/P016 and packed RGB48/RGBA64. Unknown and hardware-only pixel formats remain unset rather than defaulting to a guessed depth.
- Kept `bitDepth` and `colorRange` as decoded/source values, added the source pixel-format field, and added post-filter bit depth and color range from mpv's `video-out-params`.
- Playback Info now distinguishes Source Pixel Format / Source Bit Depth / Source Color Range from Post-filter Pixel Format / Post-filter Bit Depth / Post-filter Color Range. “Post-filter” deliberately does not claim to describe the final physical display scan-out.
- Kept mpv's existing `container-fps` value for compatibility but label it Container FPS. Added Estimated Output FPS from `estimated-vf-fps`, shown only when finite and positive. ExoPlayer's existing selected-format Frame Rate row is unchanged.
- Added common regression cases for the pixel-format depth families, unknown/hardware-only formats and invalid estimated-FPS values.

Evidence:
- mpv's [property manual](https://mpv.io/manual/stable/#command-interface-properties) defines `video-params` as decoder output and `video-out-params` as the format after video filters; it explicitly warns that `container-fps` can contain bogus values and defines `estimated-vf-fps` as a recent-frame output estimate.
- mpv's [video-filter manual](https://mpv.io/manual/stable/#video-filters) documents that the `format` filter converts to the requested pixel format, which is why source and post-filter bit depths must not be combined into one label.
- Community evidence: [mpv-android issue #423](https://github.com/mpv-android/mpv-android/issues/423) records the maintainer-confirmed trade-off that forcing `vf=format=yuv420p` converts high-bit-depth input down to 8-bit and can also reduce chroma information. [Findroid issue #701](https://github.com/jarnedemeulemeester/findroid/issues/701) includes real-device reports of 10-bit playback varying across MediaCodec/GPU paths. These reports support making the source/post-filter distinction visible; they do not justify changing Nuvio's decoder or filter defaults. A targeted Reddit search did not return a reproducible implementation, so no Reddit-only claim was used.

Verification boundary:
- Updated common and Android host test sources and re-fetched the changed production/test files from `main-build-testing` for static review.
- Gradle tests, compilation, builds, CI, benchmarks and physical-device playback were not run. The new regression sources remain unexecuted; no rendering or performance improvement is claimed.


### Android libmpv independent scaling filters — 23 November 2026

Research and implementation:
- Reviewed the current upstream mpv stable manual and mpv 0.42.0 built-in profile definitions. The `high-quality` profile sets `scale=ewa_lanczossharp`, anti-ringing and stronger HDR analysis; `fast` sets bilinear scaling, disables dithering and removes several quality corrections. The default downscaler is Hermite. These profile values are mirrored only when the user's corresponding filter selection is Default.
- Reviewed official [mpv-android MPVView.kt](https://github.com/mpv-android/mpv-android/blob/master/app/src/main/java/is/xyz/mpv/MPVView.kt), which exposes separate upscaling and downscaling filter preferences and passes them to libmpv. Nuvio now exposes the additional chroma filter independently as well.
- Added three Android libmpv settings: Upscaling Filter (`scale`), Downscaling Filter (`dscale`) and Chroma Scaling Filter (`cscale`). Choices include Bilinear, Mitchell, Spline36, Lanczos, EWA Lanczos, EWA Lanczos Sharp, Hermite and Catmull-Rom.
- The Default choice follows the selected Balanced / High Quality / Performance profile. Chroma Default follows the resolved upscaler. Explicit user choices override only their own axis.
- Filter values are applied after the initial mpv render-quality profile and are reapplied after a profile change, so selecting a new profile does not erase explicit scaling preferences. Runtime changes use libmpv's writable `options/<name>` properties on the existing serialized mpv dispatcher; media is not reloaded.
- Added profile-scoped persistence, Android settings-sync fields, iOS storage contract parity, settings rows and existing choice dialogs. Existing installs remain on Default and retain their current profile behavior.

Evidence and cautions:
- [mpv GPU renderer manual](https://mpv.io/manual/stable/#gpu-renderer-options) documents `scale`, `dscale` and `cscale` for gpu/gpu-next/libmpv, and states that cscale defaults to the selected upscaler.
- [mpv builtin.conf](https://github.com/mpv-player/mpv/blob/master/etc/builtin.conf) is the source of the built-in fast/high-quality profile values used by the resolver.
- [mpv-android issue #13886](https://github.com/mpv-player/mpv/issues/13886) records polar-scaler artifacts on some Android legacy-GPU GLES drivers; gpu-next did not reproduce the same artifact in that report. EWA filters remain user-selectable, never forced as a global default, and the settings descriptions explain their higher GPU cost and ringing risk.
- Checked current Maven metadata and upstream library tags: 0.2.2 is newer than Nuvio's 0.2.1 dependency, but its tagged changes are Vulkan symbol-loading compatibility and estimated-frame-number tick notifications, not a demonstrated scaler-quality improvement. No dependency upgrade was made without build/device verification.

Regression coverage authored:
- Common tests cover Balanced, High Quality and Performance profile defaults, independent per-axis overrides, chroma following the upscaler and profile changes retaining explicit selections.
- Android host tests cover profile-scoped persistence, settings-sync round-trip and invalid stored values falling back to Default.

Verification boundary:
- Re-fetched the changed production, settings, storage and test sources from `main-build-testing` for static review.
- No Gradle tests, compilation, builds, CI, benchmarks or device playback were run. The new tests remain unexecuted and visual quality gains have not been measured on hardware.


## Playback subtitle fidelity and late add-on recovery — 23 November 2026

### Research basis
- Reviewed upstream Nuvio Mobile PR #2698 (open at research time, updated 21 November): restore ASS/SSA font sizing while preserving authored styling by default, with an explicit opt-in for full ASS customization across Android libass, Android libmpv and iOS mpv.
- Reviewed upstream Nuvio Mobile PR #2734 (open at research time, updated 22 November): addon subtitles were incorrectly blocked when Nuvio's own backend was unreachable, asynchronous subtitle state did not invalidate Compose while paused/loading, and preferred-language selection could finish before slow addons responded.
- Checked mpv's current manual: `sub-ass-override=no` preserves authored scripts; `scale` applies `sub-scale` without forcing all style overrides; `force` applies user overrides and can disrupt authored layout. Therefore default styling remains untouched and the full override is explicitly opt-in.
- Primary implementation references: [Nuvio PR #2698](https://github.com/NuvioMedia/NuvioMobile/pull/2698), [Nuvio PR #2734](https://github.com/NuvioMedia/NuvioMobile/pull/2734), and the [mpv subtitle options manual](https://mpv.io/manual/stable/#options-sub-ass-override). Both upstream PRs were still open and unmerged when checked on 23 November; this fork ports the relevant changes independently.
- Community corroboration: [Clapper's ASS/SSA discussion](https://github.com/Rafostar/clapper/discussions/482) explains why overriding authored ASS can misplace signs and positioned text; [Kodi's subtitle forum](https://forum.kodi.tv/showthread.php?tid=349960) confirms ASS/SSA sizing is not equivalent to SRT sizing; [mpv-android issue #325](https://github.com/mpv-android/mpv-android/issues/325) records real Android user demand for adjustable subtitle size. A targeted Reddit search did not surface a comparably useful, verifiable implementation, so no Reddit claims were used.
- Community discovery also surfaced Nuvio issue #2739 requesting pinch-to-zoom. It remains an open proposal without an upstream implementation; it was not copied speculatively. Existing resize/zoom behavior was left unchanged.

### Integrated on `main-build-testing`
- Added ASS/SSA override classification: preserve authored styles at the default size, use mpv's scale-only mode when the user changes subtitle size, and use full ASS override only after enabling **Customize ASS/SSA styling**.
- Added an opt-in control only for mpv engines. ExoPlayer's libass path receives the same font-scale setting, but does not advertise unsupported full ASS styling.
- Applied font scaling to ExoPlayer libass both when the player is created and when the user changes subtitle size; invalidate the overlay for immediate preview updates.
- Applied the same scale/override/position/bold-reset behavior to Android libmpv and iOS mpv. The scale-only path restores original ASS vertical positioning instead of moving authored dialogue when font size changes.
- Fixed Android ExoPlayer libass lifecycle cleanup by routing release through `releaseWithAssSupportCompat()`, which removes the handler's scale callback state and releases the handler.
- Changed mpv overlay selection to use `usesStyledOverlay`, so non-default outline blur is not silently ignored.
- Post-integration review caught and corrected an iOS bridge forwarding omission; the Swift bridge now forwards both `customizeAss` and `assScale`, while the existing `SubtitleStyleSettings` PiP snapshot remains unchanged. New optional UI parameters were placed at the end of signatures to preserve existing positional call compatibility.
- Made addon subtitle collections/loading flags observable Compose state; distinguish no internet from an unreachable Nuvio backend; keep preferred-language auto-selection pending until addon subtitle loading has completed.

### Regression coverage authored
- Added pure ASS mode/default tests for authored-style preservation, scale-only resizing, explicit full override, non-ASS isolation and backward-compatible default preference.
- Added a Compose-state regression test for addon subtitles arriving while playback is paused/loading.
- Added a host regression test for preferred-language addon subtitles arriving after the first track scan.

### Verification boundary
- Source files, upstream PR patches and mpv manual semantics were inspected. Remote branch source changes and the final commit are checked after integration.
- No Gradle tests, compilation, build, CI, benchmark or device playback were run, respecting the instruction not to start builds or CI without authorization. Test sources are authored but remain unexecuted; runtime visual parity is not claimed until authorized Android/iOS device checks.


### Tablet subtitle readability, libass idempotence and iOS recovery audit — 23 November 2026

- Ported the concrete Android tablet subtitle-size fix from upstream Nuvio PR #2709: Android range is now 12–60sp, while iOS remains 6–40sp. Android libmpv's corresponding native font-size ceiling is raised from 122 to 183 (60 × 55/18), so the engine does not silently clamp the new UI range back to the old 40sp maximum. Upstream reports device verification on a Lenovo Xiaoxin Pad Pro GT 11.1 (Android 16); this fork's device behavior remains unverified until authorized.
- The custom Nuvio libass source confirms `AssRender.setFontScale()` advances renderer generation and the native libass setter reconfigures rendering when the scale changes. The fork previously invoked it and invalidated the overlay during repeated PlayerView recompositions even when the requested scale was unchanged. `applyAssFontScaleCompat` now normalizes non-finite values, supports the full 60sp ratio (up to 4.0x), registers the lazy-render callback once, returns whether the scale actually changed, and only triggers renderer updates/overlay invalidation on a real change.
- Audited mpv `sub-ass-override=force` against mpv issue #14800 and the option manual. `sub-ass-style-overrides` is ignored in `force` mode; the player already applies the generic `sub-bold` option, which is the correct `sub-*` override under `force`. Removed the ineffective `change-list` calls and their redundant state on Android and iOS.
- Found a real iOS recovery omission in the prior integration: the Swift `SubtitleStyleSettings` snapshot did not retain `customizeAss` or `assScale`, and the device-loss recovery path called the new style method without those required values. The snapshot now stores both fields and recovery forwards them, preserving the user's ASS mode and size after mpv device recovery. The iOS core-rebuild path also clears cached applied subtitle properties and stale PiP/Compose subtitle text before remembered settings are replayed; otherwise stale cache values from the destroyed core could suppress required writes to the new mpv instance or leave the previous cue visible.
- Evidence: [Nuvio PR #2709](https://github.com/NuvioMedia/NuvioMobile/pull/2709) (open at research time; includes tablet testing), [mpv issue #14800](https://github.com/mpv-player/mpv/issues/14800), [mpv subtitle override manual](https://mpv.io/manual/stable/#options-sub-ass-override), [Nuvio libass source](https://github.com/NuvioMedia/nuvio-libass), and [Media3 SubtitleView API](https://github.com/androidx/media/blob/release/libraries/ui/src/main/java/androidx/media3/ui/SubtitleView.java). Context7 confirms Media3 `SubtitleView.setFractionalTextSize()` sizes captions relative to the view height; the upstream PiP-specific scaling fix was already present in this fork and was not duplicated.
- Community research included mpv-android's [subtitle settings issue #495](https://github.com/mpv-android/mpv-android/issues/495), the [Plex ASS/SSA Android discussion](https://forums.plex.tv/t/is-it-so-hard-to-support-ass-subtitles-on-android-device/784751), and Reddit searches for HDR/subtitle readability. Reddit did not return a reproducible implementation worth porting; no unsupported community claim was used.
- Added regression sources for Android/iOS font range policy, the 60sp-to-183 mpv mapping, and idempotent libass scale changes. These tests were not executed.
- A fresh iOS compile, Android host tests, full build, CI, benchmark or device playback was not run. The iOS recovery correction and new Android range/performance changes are source-audited only pending authorization.


## Android manual frame-rate matching follows playback speed — 23 November 2026

The Android player's Match content frame rate → Always path manually calls Surface.setFrameRate(..., CHANGE_FRAME_RATE_ALWAYS) after disabling Media3's seamless-only Surface request. The source format's frame rate was previously passed through unchanged. That diverged from Media3's VideoFrameReleaseHelper, which derives the Surface playback frame rate as surfaceMediaFrameRate * playbackSpeed and recalculates it when playback speed changes.

Changed:
- Added the pure playbackSurfaceFrameRate(mediaFrameRate, playbackSpeed) resolver. Unknown, non-positive, non-finite and overflowed values resolve to 0f, clearing a stale request instead of sending an invalid rate.
- Centralized the manual controller's frame-rate refresh inside updateAlwaysFrameRateState, using the active ExoPlayer.playbackParameters.speed. Format changes, player state changes and controller creation now share the same update path.
- onPlaybackParametersChanged now refreshes the manual Surface request immediately, so speed changes update the display hint without waiting for a track or state transition.
- Added host regression cases for 24 fps at 1.5x (36 fps), 23.976 fps at 1.25x (29.97 fps), half-speed playback, invalid inputs and multiplication overflow.

Compatibility:
- The system preference still controls whether the manual non-seamless mode is used.
- The existing long-VOD/live eligibility gate, short-clip seamless-only behaviour, pause/end clearing, Surface lifecycle, Media3 default mode and libmpv path are unchanged.
- No dependency version, renderer, decoder, HDR pipeline or global refresh-rate policy was changed.

Evidence:
- AndroidX Media3 VideoFrameReleaseHelper source documents onPlaybackSpeed(float) and computes surfaceMediaFrameRate * playbackSpeed before calling Surface.setFrameRate: https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/VideoFrameReleaseHelper.java
- Android's frame-rate API documentation says video surfaces should report their intended fixed source rate and that platform switching is a hint, not a guarantee: https://developer.android.com/media/optimize/performance/frame-rate
- Context7 documentation for AndroidX Media3 confirms that VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF is required when the app owns CHANGE_FRAME_RATE_ALWAYS requests.
- Community research also surfaced unresolved 4K AV1 output-frame drops in mpv-android across renderer/decoder combinations on some devices. That is device-specific evidence, not a safe basis for globally changing Nuvio's renderer.

Verification boundary:
- Source-level checks confirm the manual request uses the current playback speed and is refreshed by the playback-parameters callback.
- Kotlin host tests were added but not executed. No Gradle task, compilation, build, CI, benchmark or device playback was run, as requested. The visual effect still needs a device check on a 24 fps source at 1.5x and a 23.976 fps source at 1.25x, with Android's system preference set to Always.


## HDR track selection research — 23 November 2026 (deferred pending upstream)

A separate display-quality audit found a more substantial future target in Media3's HDR adaptive-track selection:

- The project pins AndroidX Media3 1.12.0-beta01. Its release notes include the HLS VIDEO-RANGE PQ/HLG parsing change and an earlier Dolby Vision/SDR track-selection correction.
- AndroidX Media3 issue #3441 documents a reproducible HLS case where a PQ variant and SDR fallback share the same BANDWIDTH and the SDR variant can win the initial adaptive selection.
- AndroidX Media3 PR #3461 (still open when checked on 23 November 2026) proposes display-HDR-capability-aware ranking, separating SDR/PQ/HLG/Dolby Vision into distinct adaptive selections, and invalidating selections when display HDR capabilities change. This is a meaningful picture-quality issue: it can avoid selecting SDR when HDR is supported and avoid selecting unsupported HDR when an SDR fallback exists.
- The PR changes Media3's internal DefaultTrackSelector/VideoTrackInfo selection logic and display-listener lifecycle. It is not a safe one-line app-side comparator change, and it is not merged. Do not copy it blindly or replace the current player with an experimental fork. Revisit after upstream merge or after a complete app-side design with real HDR/SDR capability tests.
- mpv-android issue #1320 reports reproducible 4K AV1 output drops on a MediaTek 8450 across gpu/gpu-next and MediaCodec modes, while 1080p AV1 and 4K HEVC behave better. This is device-specific and does not justify globally forcing gpu-next or a decoder mode.
- The mpvRx repository advertises thermal-aware shader budgets, Anime4K throttling and automatic black-bar cropping. Those features are custom shader/rendering pipelines, not drop-in settings. No such pipeline was copied without profiling against Nuvio's existing shader path.

Sources: https://developer.android.com/jetpack/androidx/releases/media3 ; https://github.com/androidx/media/issues/3441 ; https://github.com/androidx/media/pull/3461 ; https://github.com/mpv-android/mpv-android/issues/1320 ; https://github.com/Riteshp2001/mpvRx

This research did not change HDR track selection, renderer choice, shader policy or decoder configuration. The next high-value display project is to revisit PR #3461's design against the exact pinned Media3 API once upstream status changes, then add device-capability and adaptive-switch regression coverage before implementation.

## AV1 HLS quality recommendation no longer assumes software decoding — 23 November 2026

- Fixed a concrete quality-selection false negative: the HLS resolver previously classified every AV1 variant as software-decoded solely from the playlist codec string, excluded it from the recommended-quality pool, and displayed a software-decode warning. Android decoder availability depends on the actual device, AV1 profile/bit depth, resolution and frame rate; the CODECS attribute alone cannot prove that this device must use software decoding.
- AV1 capability is now represented as unknown (null) at playlist-inspection time. Unknown AV1 variants remain eligible for the quality recommendation and are no longer labelled as software-only or shown with a confirmed software-decoding warning. No decoder is forced and no playback engine settings change.
- Added common regression coverage for unknown AV1 capability, accurate quality labels and retaining a higher-resolution AV1 variant in the recommendation pool.
- This is a conservative correction, not a claim that AV1 is hardware accelerated on every device. A follow-up can provide actual Android capability data by checking the selected stream's MIME/profile/level/size/rate against MediaCodec capabilities, while preserving iOS/mpv behavior.
- Evidence: https://developer.android.com/media/optimize/performance/codec ; https://developer.android.com/reference/android/media/MediaCodecList ; https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java
- Verification boundary: tests were authored but not executed. No Gradle test, compilation, build, CI, benchmark or device playback was run, respecting the no-build/no-CI instruction.


## Runtime decoder failure recovery — upstream status and app gap (23 November 2026)

A separate resilience review checked whether the existing `setEnableDecoderFallback(true)` protects playback when a decoder initializes successfully and crashes later during playback. It does not: Media3's built-in fallback covers decoder initialization failures, not runtime codec crashes.

Findings:
- AndroidX Media3 issue [#3421](https://github.com/androidx/media/issues/3421) documents real mid-stream decoder crashes, including a MediaTek HEVC failure after successful initialization.
- AndroidX Media3 PR [#3422](https://github.com/androidx/media/pull/3422) proposes excluding the failed decoder and continuing with another decoder. It remained open and unmerged when checked on 23 November 2026. Its approach changes Media3 renderer/decoder selection and recovery internals; it is not part of the project's pinned 1.12.0-beta01 release.
- Jellyfin Android TV PR [#5827](https://github.com/jellyfin/jellyfin-androidtv/pull/5827) describes a device-verified app-side workaround using a failed-decoder tracker, a custom `MediaCodecSelector`, and local re-prepare/seek. The PR was closed without merging. Its author reports recovery on one Philips/MediaTek TV, but that is not broad device validation and the implementation was not copied.
- Nuvio already enables initialization fallback and logs the selected video decoder, dropped-frame events and processing offsets. The Android `AnalyticsListener` currently does not override `onVideoCodecError`, so a codec error that Media3 recovers from is not separately recorded with its decoder identity and playback position. Adding this telemetry is a small, testable next step; automatic runtime decoder exclusion/retry is a separate, higher-risk behavior change.

Decision:
- Do not implement an app-level runtime decoder retry in this pass. It can change decoder choice, DRM/secure decoder behavior, playback position and recovery lifecycle. Upstream PR #3422 remains active, and Jellyfin's device-specific workaround is not sufficient evidence for a global policy.
- Do not change decoder ordering, force software decoding, or claim that initialization fallback handles runtime crashes.
- Before any retry implementation, add failing host tests for decoder exclusion lifetime, repeat failures, no-alternative-decoder behavior, retry position/state restoration and queue-item reset. Then validate on affected physical devices with a real runtime decoder failure.

Sources: https://github.com/androidx/media/issues/3421 ; https://github.com/androidx/media/pull/3422 ; https://github.com/jellyfin/jellyfin-androidtv/pull/5827 ; https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener

Verification boundary:
- Repository source inspection confirmed the current fallback flag, decoder initialization logging, dropped-frame logging and missing codec-error callback.
- No production code or tests were changed in this research-only cycle. No Gradle task, compilation, build, CI, benchmark or device playback was run, respecting the no-build/no-CI instruction.


## Playback colour-pipeline visibility and codec-error telemetry — 23 November 2026

A targeted display audit found that Nuvio already captured source colour metadata and some libmpv post-filter values in the detailed playback-info JSON, but did not expose the full source-versus-output colour path in the compact Stream Info overlay. The compact `PlayerMediaInfo` model also omitted those values. This made it unnecessarily difficult to distinguish an HDR source from the colour parameters and pixel format produced after libmpv's video filters.

Implemented:
- Extended the shared `PlayerMediaInfo` model with source primaries, transfer function, matrix and range, plus decoded/post-filter output dimensions, pixel format, bit depth, primaries, transfer, matrix and range. Values remain nullable when the engine does not expose them.
- Populated the source and post-filter fields from libmpv's `video-params/*` and `video-out-params/*` properties. The output properties are explicitly the post-filter output parameters, not a claim about the physical panel's final scanout or measured display gamut.
- Populated ExoPlayer source colour primaries, transfer and range from Media3 `Format.colorInfo`, and its decoded dimensions from `Player.videoSize`. Media3 does not expose an equivalent complete post-filter colour tuple here, so those fields are intentionally left unknown rather than copied from source metadata.
- Added a separate Decoded Video Output section to Stream Info, and extended Playback Info to show decoded output dimensions and libmpv post-filter primaries, transfer function and matrix alongside the existing pixel-format, bit-depth and range details. This makes HDR-to-SDR conversion and resolution changes easier to inspect without altering playback colour transforms.
- Added Android host regression cases for distinct source/output colour metadata and for unavailable output properties remaining unknown.
- Added Media3 `onVideoCodecError` and `onAudioCodecError` diagnostic callbacks. Logs include decoder identity, playback position, playback attempt and the existing sanitized throwable chain. These callbacks are diagnostic only; no automatic retry or user-visible error is triggered because Media3 explicitly says codec-error callbacks may be non-fatal and playback may recover internally.

Evidence and community research:
- Android's [HDR playback guidance](https://developer.android.com/media/grow/hdr-playback) recommends SurfaceView for HDR; TextureView can force HDR-to-SDR conversion with possible clipped colours and banding on Android 13+. The existing player already uses SurfaceView, so no duplicate renderer change was made.
- The mpv [property manual](https://mpv.io/manual/stable/#property-list) distinguishes decoded/source `video-params` from post-filter `video-out-params`. Upstream mpv's [stats.lua](https://github.com/mpv-player/mpv/blob/master/player/lua/stats.lua) reads both sets, corroborating the source/output separation used here.
- Community reports include [Nuvio HDR colour complaints](https://github.com/MustardChef/WSABuilds/issues/716), [Wholphin's HDR-to-SDR issue](https://github.com/damontecres/Wholphin/issues/545), [Findroid's MPV colour-shift reports](https://github.com/jarnedemeulemeester/findroid/issues/645), and [mpv-android's hardware-decoding colour issue](https://github.com/mpv-android/mpv-android/issues/1074). These reports vary by device, renderer and decoder; they support better source/output diagnostics, not a universal shader, forced tone-map or decoder policy.
- AndroidX Media3's [AnalyticsListener source](https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/analytics/AnalyticsListener.java) documents the codec-error callbacks as diagnostic, with fatal playback errors handled through `Player.Listener.onPlayerError`.

Safety/compatibility decisions:
- No global tone mapping, shader, scaling kernel, decoder ordering, SurfaceView/TextureView switch, or Media3 internal fork was introduced. Community evidence shows these paths are device-specific and can trade image quality for dropped frames or unsupported output.
- The output metadata is descriptive only. It must not be presented as proof of panel HDR support, successful HDR passthrough, or visually correct tone mapping.
- Runtime decoder exclusion/retry remains deferred while upstream Media3 PR [#3422](https://github.com/androidx/media/pull/3422) is open. The new codec callbacks only record evidence.

Verification boundary:
- Source files and existing property semantics were reviewed against Media3 Context7 documentation, Android HDR guidance, the mpv property manual and upstream mpv stats.lua.
- Regression tests were authored but not executed at the user's request. No test task, compilation, build, CI, benchmark or physical-device playback was run. The UI labels and actual output values still require manual playback review later, especially HDR10/PQ input converted to SDR output and a resolution-changing mpv filter chain.


## Display-aware HDR track selection for ExoPlayer — 23 November 2026

A display-quality review found a concrete Android fallback gap in the pinned Media3 1.12.0-beta01 selector. HLS PQ/HLG signalling is parsed in this release, but Media3's separate display-capability-aware selection change remains an open upstream pull request. The current selector can prefer a decoder-supported HDR track even when the default display does not report support for that HDR type, including mixed HDR/SDR groups when the display is SDR-only or has HDR disabled.

Implemented on Android ExoPlayer:
- Added `DisplayAwareDefaultTrackSelector`, a small app-side subclass that evaluates the current default display's `Display.getHdrCapabilities().supportedHdrTypes` during video selection.
- When a renderer has a supported SDR alternative, the selector masks only signalled PQ/HLG/Dolby Vision formats that the display cannot present. It delegates the actual quality, viewport, language, decoder and adaptive selection algorithm to Media3, so SDR adaptive selection remains available across the remaining supported formats.
- If the default display itself is unavailable or capability lookup throws, the selector leaves Media3's original format support untouched. Android's documented `null` HDR-capabilities result for an available display is normalized to an empty HDR-type set (SDR-only), rather than being mistaken for unknown. If no supported SDR alternative exists, HDR-only playback remains available rather than being disabled.
- Dolby Vision decoded with a fallback MIME type is evaluated using its PQ/HLG base-layer transfer. Dolby Vision decoded by a primary Dolby Vision decoder requires display Dolby Vision support. PQ requires HDR10 specifically; an HDR10+ capability alone is not treated as sufficient because Media3 Format does not expose the dynamic metadata needed to distinguish that case.
- A default-display listener refreshes the capability snapshot and calls Media3's supported track-selection invalidation path when HDR types change. It is unregistered when the selector is released.
- The implementation copies Media3's renderer-support matrix before masking formats; it never mutates the framework-owned capability arrays.
- Added regression cases for PQ/HDR10, HLG, Dolby Vision primary/fallback decoder paths, HDR10+ without HDR10, unknown display capabilities, unsupported decoder formats, preserving HDR-only streams, and ensuring the explicit force-lowest-bitrate policy bypasses the display filter.

Research and source evidence:
- AndroidX Media3 [PR #3461](https://github.com/androidx/media/pull/3461) remains open and contains the upstream display-aware ranking, HDR/SDR adaptive separation, and display-change invalidation design. Its behavior is the reference for the conservative app-side fallback here; no unmerged Media3 internals were copied.
- AndroidX Media3 [PR #3446](https://github.com/androidx/media/pull/3446) is merged and its HLS PQ/HLG `VIDEO-RANGE` parsing is included in the 1.12.0 alpha/beta line used by this repository. This gives the selector real transfer metadata for those HLS variants.
- Media3's exact pinned `DefaultTrackSelector` source exposes the protected `selectAllTracks` extension point. `TrackSelector.invalidate(null)` is documented as callable from application or playback threads and re-evaluates selections without replacing user parameters.
- The upstream PR specifically maps PQ to HDR10, HLG to HLG, and primary Dolby Vision decoding to Dolby Vision. It also documents that HDR10+ without HDR10 must not be treated as sufficient for PQ when dynamic metadata is not represented in `Format`.
- mpv-android [issue #874](https://github.com/mpv-android/mpv-android/issues/874), updated 19 November 2026, remains an open native-HDR request. Community reports continue to show device- and renderer-specific behavior; they do not justify changing Nuvio's global mpv output or tone-mapping configuration in this pass.
- The [Kodi Android HDR/tone-mapping forum thread](https://forum.kodi.tv/showthread.php?tid=374021) describes the key platform trade-off: MediaCodec Surface output leaves tone mapping to the device/vendor path, while app-controlled GPU tone mapping requires a different render path and can be too expensive for 4K HDR on mobile hardware. This is why no forced software tone mapper was added.
- The mpv [HDR-to-SDR gpu-next discussion](https://github.com/mpv-player/mpv/discussions/14757) contains useful community profiles, but contributors report that some shaders struggle on Android; it is not a device-independent default. The Reddit/community search did not surface a reproducible Android HDR recipe with enough device, codec and display detail to justify changing defaults.
- mpv's current manual recommends `gpu-next` and provides separate fast/high-quality profiles. Nuvio already exposes Balanced/Performance/High Quality render profiles, scaling filters and debanding settings. Those controls were not duplicated or globally changed. The manual and mpv maintainer discussion also caution that scaling preference is content-dependent and GPU-heavy processing can fail on mobile GPUs.

Compatibility and non-goals:
- Only the Android ExoPlayer track selector changes. libmpv, iOS, SurfaceView, renderer choice, video effects, frame-rate matching, decoder priority and user quality settings are unchanged.
- Explicit user track overrides are still applied by Media3 after automatic selection, preserving the user's deliberate manual choice. The app-side HDR preference is bypassed when `forceLowestBitrate` is enabled, matching Media3's upstream exception.
- This is not an HDR tone-mapping implementation and does not claim to fix every washed-out image. It prevents automatic selection of a signalled HDR format that the current default display does not advertise support for when a supported SDR fallback exists.
- If the display reports no capability information, or a format has no reliable PQ/HLG/Dolby Vision signal, the selector deliberately leaves the format eligible.

Verification boundary:
- Added Android host regression sources; they were not executed, as requested.
- Source-level review checked the override signature, support-flag APIs, Dolby Vision fallback decoder flag, display listener lifecycle, and invalidation contract against the exact Media3 1.12.0-beta01 source.
- No Gradle tests, compilation, build, CI, benchmark or physical-device playback was run. The visual result still requires later device validation with HDR10, HLG, Dolby Vision, SDR fallback, HDR-only content, and display HDR toggling.


### Follow-up: preserve explicit lowest-bitrate selection

The app-side display filter now bypasses itself when Media3's `forceLowestBitrate` parameter is enabled. This keeps the user's explicit lowest-bitrate policy ahead of display HDR preference, matching the exception in upstream PR #3461. Added focused regression coverage for the bypass and for unknown display capabilities.


### Follow-up: handle Android's forced-SDR capability response

The source review identified that `Display.getHdrCapabilities()` may return `null` when the default display is forced to SDR. The selector now normalizes a present display with null HDR capabilities to an empty supported-type set, while still treating a missing display service/display or a capability-query exception as unknown. This distinction is necessary for the SDR fallback to activate when HDR has been disabled in Android display settings. Added regression coverage for null capabilities mapping to SDR-only and for retaining reported HDR types.


### Follow-up: only count genuinely supported SDR alternatives

A source review against the pinned Media3 1.12.0-beta01 RendererCapabilities contract found an edge case in the display-aware selector: isFormatSupported(flags, true) treats FORMAT_EXCEEDS_CAPABILITIES as supported. That value is explicitly a last-resort format outside the renderer's declared capabilities, not proof that the decoder can reliably play the track. The selector could therefore treat an unsupported SDR rendition as a usable fallback and mask an HDR rendition that was actually supported, potentially turning a stream with no playable SDR alternative into an avoidable playback failure.

Correction:
- The selector now uses strict renderer support (allowExceedsCapabilities = false) both when deciding whether an SDR fallback exists and when deciding whether to mask an HDR format.
- Added focused regression coverage for SDR/HDR formats marked FORMAT_EXCEEDS_CAPABILITIES, supported SDR fallback recognition, positive HLG display support, and Dolby Vision fallback-MIME codec strings using their PQ base layer.
- This preserves Media3's own final fallback policy; it only prevents the display filter from treating a marginal/unsupported SDR format as a safe replacement.

Evidence:
- Pinned source: [Media3 1.12.0-beta01 RendererCapabilities.java](https://github.com/androidx/media/blob/1.12.0-beta01/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/RendererCapabilities.java). Its isFormatSupported implementation explicitly allows FORMAT_EXCEEDS_CAPABILITIES only when the caller passes true.
- Context7 confirms that allowExceedsCapabilities = true includes FORMAT_EXCEEDS_CAPABILITIES; strict support uses false.
- Upstream [Media3 PR #3461](https://github.com/androidx/media/pull/3461) remains open as of this review. Its HDR/SDR adaptive separation work is not present in the app-side selector and is not being approximated by masking an entire range, because that would remove bitrate adaptation and could cause avoidable stalls.

Verification boundary:
- Regression tests were added but not executed, as requested. No tests, compilation, build, CI or device playback were run.


### Follow-up: keep HDR fallback detection inside the same TrackGroup

A subsequent review found that the first app-side selector implementation searched every video TrackGroup on a renderer for any supported SDR track, then used that single result to decide whether to mask HDR formats in every group. This is too broad: Media3 defines a TrackGroup as alternate formats of the same content, while separate groups may represent a different camera angle or video feed. An unrelated SDR feed must not be treated as a fallback for an HDR-only group.

Correction:
- Moved the SDR fallback check inside the per-TrackGroup loop. An HDR format is now masked only when a genuinely supported SDR alternative exists in that same group.
- Kept the strict renderer-support check, existing display capability rules, and Media3's own selection/quality algorithm unchanged.
- Added host regression cases for an HDR-only group alongside a separate SDR group, and for HDR/SDR variants within the same group.

Evidence:
- The exact pinned Media3 1.12.0-beta01 `TrackGroup.java` documentation states that all tracks in a group present the same content, and gives separate camera angles as separate groups.
- The exact pinned Media3 `DefaultTrackSelector.selectTracksForType` implementation creates adaptive candidates within each TrackGroup; it does not combine tracks across groups.

Verification boundary:
- The implementation and regression sources were updated on `main-build-testing`; tests were not run. No compilation, build, CI or device playback was performed.

### Android Auto engine: recognize standardized HDR transfer labels

The Auto engine resolver previously recognized only the consumer labels `HDR`, `HDR10`, `HDR10+`, `DV`, and `Dolby Vision`. This left real-world metadata forms such as `HLG`, `PQ`, `ST 2084`, `SMPTE ST 2084`, `BT.2100`, `ARIB STD-B67`, `HDR10Plus`, `DOVI`, and separator variants of Dolby Vision undetected. When an anime identifier/genre was also present, the resolver could consequently choose libmpv instead of the ExoPlayer path that Nuvio already uses for HDR-tagged streams.

Changes:
- Expanded whole-token detection for HDR10/HDR10+, HLG/HLG10, PQ, Dolby Vision/DOVI and standardized PQ/HLG transfer identifiers.
- Accepted common punctuation/spacing variants while preserving boundaries, so strings such as `DVDRip`, `PQ10`, and suffixed unrelated words do not trigger HDR routing.
- Kept the existing priority order: an explicit engine choice is never overridden; in Auto mode recognized HDR/Dolby Vision metadata takes precedence over anime routing; non-HDR anime selection is unchanged.
- Updated the Android playback-engine setting descriptions to explain that Auto routes tagged HDR/Dolby Vision to ExoPlayer and that ExoPlayer provides native HDR output when the device/stream support it. libmpv's Android GPU path tone-maps HDR to SDR rather than providing native HDR passthrough.

Evidence:
- The pinned app dependency is `io.github.abdallahmehiz:mpv-android-lib:0.2.1`; its upstream README describes it as a libmpv Android wrapper, while the upstream mpv-android native-HDR discussion documents the Vulkan-specific path and limitations. Nuvio's Android libmpv choices are `gpu-next` and `gpu`, and both already describe Android HDR-to-SDR tone mapping in `PlayerModels.kt`.
- Android's [HDR playback guidance](https://developer.android.com/media/grow/hdr-playback) recommends ExoPlayer for HDR and `SurfaceView` for native HDR output; it warns that `TextureView` converts HDR to SDR on Android 13+ with potential detail loss.
- mpv-android [issue #874](https://github.com/mpv-android/mpv-android/issues/874) documents that native Android HDR requires the Vulkan output path; the standard OpenGL path tone-maps HDR. This is community/maintainer evidence, not a claim that every custom mpv build has identical capabilities.
- Android's [HDR format guidance](https://developer.android.com/media/grow/hdr) identifies HLG/PQ transfer standards and SMPTE ST 2084; these are valid HDR identifiers beyond the consumer "HDR10" label.

Verification boundary:
- Expanded common-test regression cases cover HLG/HLG10, PQ, HDR10Plus, DOVI, Dolby Vision punctuation, SMPTE ST 2084, BT.2100, ARIB STD-B67 and false-positive token boundaries. Tests were authored but not run, as requested.
- No Gradle tests, compilation, build, CI, benchmark or physical-device playback was run. Auto routing remains metadata-driven: if an addon supplies no HDR/Dolby Vision signal in filename, name, description or title, this resolver cannot infer the bitstream's actual transfer characteristics before playback.

## Selected subtitle format controls Android cue normalization — 23 November 2026

A focused playback-path review found two related subtitle metadata defects in Android ExoPlayer's `presentCues` callback:

- The callback found a selected text `Tracks.Group` but always read `getTrackFormat(0)`. A group can contain multiple tracks and the selected subtitle need not be index zero, so the presenter could classify a different format from the one selected by the user.
- The SSA normalization guard used `mimeType != TEXT_SSA && codecs != TEXT_SSA`. That allows normalization whenever either field identifies SSA but the other field is absent or different. SSA positioning must be preserved when either metadata field identifies the format.

Correction:
- Added `selectedSubtitleFormat(Tracks)` to walk text groups and read a track format only from a track marked selected by Media3.
- Added `isSsaSubtitleFormat(Format?)`, recognizing SSA from either the sample MIME type or a comma-separated codec token.
- Added `shouldNormalizeSubtitleCuePosition(Format?)`. Unknown format metadata retains the previous normalization fallback; an SSA format disables normalization.
- Routed Android ExoPlayer cue presentation through these helpers. Sidecar suppression and sidecar subtitle handling remain unchanged.
- Added `SelectedSubtitleFormatTest` cases for non-zero selected indices, no selected track, SSA MIME-only metadata, SSA codec-only metadata, and unknown-format fallback.

Evidence:
- The AndroidX Media3 [Tracks.Group source](https://github.com/androidx/media/blob/release/libraries/common/src/main/java/androidx/media3/common/Tracks.java) exposes `isTrackSelected(index)` and `getTrackFormat(index)`. Its documentation explicitly says multiple tracks may be selected in adaptive groups and that `Tracks.Group` does not expose which selected format is currently active. The helper therefore reads the first selected text format only for format classification; it does not claim to identify the active adaptive rendition.
- The implementation uses the existing Media3 track metadata API and adds no dependencies or renderer changes.

Verification boundary:
- Test source was added before the production edit. The tests were not executed because no Gradle test/build or CI run was authorised in this cycle. No build, CI, benchmark or device playback was run.
- This is a source-level correction to subtitle format classification and SSA cue positioning; the visual result still requires later playback validation with SSA/ASS and non-SSA tracks, including a subtitle group where the selected track is not index zero.
- The separate NuvioTV HLG alternative-transfer-SEI implementation was reviewed. It modifies a Matroska extractor at sample-read time; this app currently wires `DefaultExtractorsFactory`, so transplanting that implementation without an extractor integration plan would be a broad, risky fork. No speculative HDR parser fork or mpv tone-map default was introduced.

