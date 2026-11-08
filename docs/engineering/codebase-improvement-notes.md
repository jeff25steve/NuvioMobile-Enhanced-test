# Nuvio Mobile Enhanced — Engineering Improvement Notes

**Repository:** `jeff25steve/NuvioMobile-Enhanced-test`  
**Working branch:** `main-build-testing`  
**Review started from:** `e86df525fa0c633adc753467220592d45bb0a1f9`  
**This continuation rechecked:** `a9f6d930cb6ee436046bf54b9e1e4533e993cf62`  
**Live TV continuation base:** `75fa9c2d6fcabb104320ff2b41c9d1ee2e9e4217`  
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

## Current improvement batch

### Trakt metadata hydration — episode coordinate index

The hydration path previously called `meta.videos.firstOrNull` for every progress entry and repeated the scan if an episode mapping remapped the coordinates. The same `MetaDetails.videos` list is stable for the duration of one hydration operation, so it can be indexed once by `(season, episode)`.

The index is only built for multi-entry hydration batches that contain episode coordinates; a single-entry batch retains the original short-circuit search so it does not pay for a one-use map. It keeps the **first** video for duplicate coordinates, matching the old `firstOrNull` behaviour. Entries with either coordinate missing are not indexed; callers only query when both are non-null. Remapped coordinates use the same index. For multi-entry batches this changes repeated expected linear lookups into one index pass plus expected constant-time lookups, at the cost of a temporary map. It does not change progress ordering, metadata precedence, remote state or UI behaviour.

### Home collection hero fallback — bounded source collection

The home fallback only requests the first six collection sources, but previously flattened every collection's folders and every folder's resolved sources before taking that prefix. A bounded nested traversal now stops at the same six-source boundary while preserving collection/folder/source encounter order and the existing `CollectionFolder.resolvedSources` precedence (explicit `sources` wins; legacy `catalogSources` are converted only when explicit sources are empty).

This is a fallback path, not a frame-rendering optimisation. The benefit is avoiding fully flattened intermediate lists on that path. The helper still reads a folder's existing `resolvedSources` property, which may materialise the legacy catalog list for that one folder.

## Investigated and intentionally left unchanged

- **AddonRepository pending-state reconstruction:** it rereads live `_uiState.value` while refresh jobs are marked, so snapshotting the list once could change concurrent refresh/profile behaviour. The list is also normally small. Leave until a concurrency-safe state transition is designed and covered.
- **Downloads completion detection:** the status comparison uses previous state and duplicate IDs; a persistent index would add synchronisation/lifecycle complexity. Do not change without profiling.
- **TMDB person-credit ordering:** filtering occurs before stable vote sorting and deduplication after sorting, so the first/highest-ranked duplicate wins. Moving filtering after sorting or deduplicating earlier changes work or semantics.
- **Sequence conversion:** not a general optimisation rule. Choose based on data size, terminal operators, and representative benchmarks.

## Research references

- Jake Wharton, [Intermediate collection avoidance](https://jakewharton.com/intermediate-collection-avoidance/) (7 February 2024): measured allocation reductions from fusing transformations where the destination operation supports it.
- Kotlin Discussions, [Using sequences to avoid copying large collections](https://discuss.kotlinlang.org/t/using-sequences-to-avoid-copying-large-collection-performance/24500) (30 March 2022): sequences are context-dependent; operator overhead and eager sizing matter.
- Kotlin Discussions, [Sequences and inlined lambdas](https://discuss.kotlinlang.org/t/sequences-and-inlined-lambdas/17228/12): JMH example showing substantial cost from per-element temporary lists in a particular `flatMap` pattern.
- Effective Kotlin, [Consider associating elements to a map](https://kt.academy/article/ek-associate): explains amortising repeated linear searches by building a keyed index and calls out the up-front construction trade-off.
- Stack Overflow, [Enhancing a nested for-each lookup](https://stackoverflow.com/questions/64954986/enhancing-the-performance-of-a-for-each-loop-that-has-an-inner-for-each-loop): practical list-to-map indexing pattern for repeated matching.
- Chris Banes, [public performance discussion](https://witchsky.app/profile/did:plc:ebql4lg3squ76xpzwer5ko56/post/3li2mub7px22j): benchmark results and practitioner discussion showing that sequences are not universally faster.
- Nish Tahir, [Benchmarking Kotlin Collections API performance](https://nishtahir.com/benchmarking-kotlin-list-tranformation-performance/): includes Android/JVM allocation measurements and the important backing-list caveat.

Reddit searches in the reviewed pass did not return a sufficiently relevant, verifiable discussion to use as evidence. Do not claim Reddit independently validates these changes.

### TMDB metadata caches — synchronized access and bounded retention

The TMDB service had nine process-wide mutable result maps (enrichment, episode enrichment, recommendations, collections, trailers, people, entity browse, entity headers and entity rail pages). Unlike the backdrop cache, which already uses a coroutine mutex and a 256-key cap, these maps had no synchronization or capacity limit. Their accesses are spread across Dispatchers.Default calls and parallel child coroutines: entity browse starts header and rail requests together, while the details path concurrently loads metadata enrichment groups. Concurrent reads/writes against a regular mutable map are not safe, and browsing many different titles, people or entities could retain results for the process lifetime.

A new common-code SynchronizedBoundedCache uses the existing Kotlinx AtomicFU SynchronizedObject / synchronized multiplatform mechanism to guard reads, writes, prefix iteration and clear operations. It uses insertion-order FIFO eviction and fixed per-cache entry caps: 32 for enrichment, episode, collection, trailer and person results; 64 for recommendation pages and entity headers; 16 for assembled entity browse data; and 128 for individual entity rail pages. Updating a key does not refresh its insertion position, and reads do not change eviction order. This deliberately preserves cachedOriginalTitle's first-insertion prefix lookup semantics. Cache keys, cached values, network requests, title/source priority, returned models and UI behaviour are unchanged. The existing separately bounded backdrop cache and its mutex are untouched.

This is a count bound, not a byte-accurate memory budget: individual result sizes can vary, so the limits are conservative safeguards rather than a measured peak-memory guarantee. There is no TTL or single-flight request coalescing; concurrent misses may still issue duplicate requests, as before. Those are separate policy changes and were not added without evidence that their complexity is warranted. No dependency was introduced; AtomicFU and kotlinx-coroutines-test were already used by the project.

Regression coverage was added for oldest-entry eviction, replacement/prefix ordering and concurrent reads/writes from Dispatchers.Default. The tests have not been executed because no Gradle test/build task or CI workflow was authorised in this task.

### Additional live-branch changes rechecked

The branch had advanced during this review and was re-read at a9f6d930cb6ee436046bf54b9e1e4533e993cf62. It now includes upstream Anime4K player enhancement work and the separate cbc9bde shared HTTP response buffering allocation change. The latter replaces a per-read temporary chunk list/array-copy pipeline with Ktor's bounded channel buffer read and adds boundary/UTF-8 tests; that opportunity is already implemented and was not repeated.


### Live TV — profile lifecycle, account cleanup and cache identity

The Live TV storage adapters already suffix preferences with `ProfileScopedKey`, but the process-wide `LiveTvRepository` kept `hasLoaded`, `channelsRequested`, channels, favourites, recents, provider settings, EPG state and active loader jobs across profile changes. `ProfileRepository.selectProfile` did not notify Live TV, and `LiveTvScreen` used a one-shot `remember` initializer, so a retained screen could keep rendering the old profile and old-profile refresh/EPG work could complete after the switch.

The repository now resets its Live TV state and cancels both loader jobs when the active profile changes, then reloads the new profile's local settings without forcing a network fetch when the Live TV screen is not active. It also records the account user ID and profile index bound to its loaded state and checks that identity whenever settings are loaded; this covers the initial-screen/authentication race where the first load can begin before AuthRepository leaves its Loading state. The screen observes the existing auth/profile StateFlows and keys its channel-load effect by account user ID and active profile index, so an already-composed screen requests channels again when either identity changes. This uses the existing lifecycle-aware Flow collection and Compose effect-key cancellation pattern; no visual elements or website-owned settings were changed.

The account wipe had a separate omission: Android's `nuvio_live_tv` SharedPreferences store and all eleven iOS profile-suffixed Live TV keys were absent from the platform cleanup lists. The Live TV settings include provider usernames/passwords. The account cleaner now clears the in-memory repository, waits for its channel/EPG loader jobs to stop, clears and read-verifies both shared disk-cache payloads, and includes those platform preference stores/keys in the existing account wipe. The iOS native navigation visibility key is removed as part of the same wipe. The Stalker portal session cache is now synchronised and generation-guarded: clearing it on profile/account reset prevents a handshake started under the previous identity from repopulating the cached credential/token after the reset.

The channel-cache signature was also incomplete and collision-prone. It omitted Stalker passwords, used 32-bit `String.hashCode` for playlist URLs and provider passwords, omitted playlist names even though cached channel objects retain `playlistName`, and did not include account/profile identity although cache files are shared across profiles. It now hashes a versioned, length-delimited representation with the app's existing platform SHA-256 implementation. It includes the authenticated account ID and active profile index, enabled playlist IDs/types/names/full source URLs, plus enabled Xtream/Stalker endpoints and credentials (including Stalker password), while only persisting the digest rather than raw IDs, credentials or URLs. EPG signatures similarly hash the complete ordered request keys. Old cache entries naturally miss the new versioned signature and are refreshed once; provider selection order and loaded channel models are otherwise unchanged.

New common tests cover Stalker password invalidation, a deliberate same-length `String.hashCode` URL collision, playlist rename invalidation and exact EPG request-key identity. These tests are added but remain unexecuted because Gradle tests/builds and CI were not authorised.

### Additional paths reviewed and left unchanged

The download pipeline already rate-limits Android repository progress callbacks to roughly one second and applies both time/byte-delta gates on iOS; a second throttle would be redundant and could weaken resume/persistence semantics. Continue-watching enrichment is already synchronised, bounded by profile/source cache scope and cleared on profile/account changes. Stream-link and binge-group data already use profile-scoped keys in both platform storage adapters, so those paths were not modified.

Research basis for the Live TV lifecycle change: Android's official [Compose side-effects guidance](https://developer.android.com/develop/ui/compose/side-effects) documents that `LaunchedEffect` cancels and restarts when its keys change, and official [lifecycle-aware coroutine guidance](https://developer.android.com/topic/libraries/architecture/coroutines) recommends `collectAsStateWithLifecycle` for StateFlow-backed UI state. Kotlin's [shared mutable state and concurrency documentation](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html) supports synchronising the Stalker session cache accessed across dispatchers. The branch's Android/iOS adapters and upstream `enhanced` implementations were inspected directly; the Live TV lifecycle and storage-cleanup omissions were still present upstream. Reddit searches did not surface a sufficiently relevant, source-verifiable Live TV/KMP implementation, so no Reddit claim is used.


### Account cleanup — complete profile-scoped storage parity

A second platform-by-platform audit found that the account cleaner did not cover every profile-scoped preference store. Android left several entire SharedPreferences files behind; iOS had a hand-maintained base-key inventory that only covered a subset of the actual storage adapters. This meant profile-specific settings or personal search history could survive a local account wipe even though the same data was correctly separated by profile during normal use.

Android cleanup now includes the omitted profile stores for custom poster URL rules, card-depth style, metadata-screen settings, search history, season view mode, player track preferences, TMDB settings, Trakt comments, resume-prompt state, binge-group cache and user-rating preferences. Each store name was checked against its actual Android adapter.

The iOS cleaner now covers the corresponding profile-scoped key families, including the complete player-settings and theme-settings key sets, TMDB/debrid/MDBList/P2P settings, stream badge preferences, custom poster/card-depth/meta-screen/search settings, season view mode, resume prompt, and Trakt comments. It also clears profile-indexed user-rating keys and uses narrow dynamic prefixes for per-title player-track settings, binge-group cache entries, and debrid device-authorization state. Existing dynamic stream-link and continue-watching cache prefixes remain covered. It does not clear the entire defaults domain or unrelated device-level settings.

The in-memory custom-poster and search-history repositories are now reset along with the existing cleanup. TMDB, MDBList and debrid settings repositories are reloaded from the cleared active-profile storage so API keys and service configuration do not remain in their process state after the wipe. These calls are local reads only; they do not trigger service synchronization.

Downloaded media and its profile-scoped download registry/location are intentionally left untouched in this change: deleting the registry without also handling the user-selected storage destination would orphan local media, while deleting those files would be a destructive behavior change. Device-wide configuration (server endpoint, sync client identity, Sentry/Discord settings and public metadata caches) is also preserved because it is not profile-scoped account data.

The storage comparison used the actual Android and iOS storage adapters and the common `ProfileScopedKey` contract (`baseKey_profileIndex`). No blanket UserDefaults wipe or changes to website-managed configuration were introduced. Builds, tests and CI remain unrun by instruction.


### Account cleanup — membership cache ownership and late refresh prevention

The persisted supporter entitlement payload already contains its owning `userId`, but `MemberAccessRepository.hydrateCachedAccess` previously loaded it without checking the active auth state or comparing that owner ID. During app startup, this could briefly expose a previous account's entitlement while `AuthRepository.state` was still `Loading`, or after a different account became active.

Startup hydration now requires a resolved, non-anonymous `AuthState.Authenticated` whose ID matches the stored payload. The same ownership check is used before publishing cache or remote results. Remote requests capture a generation and recheck both that generation and the current authenticated owner before they publish or persist; `clearLocalState` increments the generation under the app's existing multiplatform AtomicFU lock, so a request started under the previous account cannot repopulate the cache after the clear.

The cleaner establishes a short-lived membership cleanup barrier at the very start of the full account wipe, before profile image, PIN, platform preference or repository cleanup begins. It prevents periodic refreshes, auth-driven loads and startup cache hydration while the wipe is in progress, without changing when the app transitions to the sign-in UI. The barrier is reference-counted across overlapping cleanup attempts and is released only when the last outer `finally` completes, even when an earlier cleanup operation fails. If a different authenticated account became active during the wipe, its membership is refreshed after the barrier is released. A same-account re-authentication observed during the wipe is also detected and refreshed after cleanup; the original account remains blocked until the normal unauthenticated auth-state event.

Common tests were added for Loading, Unauthenticated, anonymous, matching-owner and different-owner cache hydration, plus stale-generation and cleanup-barrier decisions. They were not executed because Gradle tests/builds and CI remain prohibited until explicit authorisation. The implementation uses existing AtomicFU, Kotlin 2.4.10 and kotlinx.coroutines 1.10.2. No dependency, Gradle, resource, signing or CI configuration was changed.


### Watched delta sync — lazily index stable fallback deletes

The watched delta page size is 900 events. When a remote DELETE event's constructed key was absent locally, `applyWatchedDeltaEvents` previously scanned the complete local watched map to find the first row matching content ID, compatible media type, season and episode. Several fallback deletes in a page could therefore repeatedly scan the same large local collection.

A lazy `WatchedStableDeleteIndex` is now created on the second mismatched-key DELETE in a page. The first fallback retains the original single scan; pages with zero or one fallback delete therefore allocate no index. For repeated fallbacks, it groups rows by the exact stable identity used by the previous predicate and keeps keys in the backing map's observed iteration order, so the first compatible alias remains the deletion target. Once created, it is updated for every later upsert and removal in the same event sequence. Dirty-key preservation and direct exact-key deletes are unchanged.

This uses Kotlin's documented insertion-order guarantee for `mutableMapOf`/default `LinkedHashMap` ([mutableMapOf API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/mutable-map-of/), [LinkedHashMap API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-linked-hash-map/)), and the existing `WatchedItemsStore` explicitly creates its Nuvio/provider item maps with `mutableMapOf`. The index is scoped to a single delta page and is not retained between refreshes or profiles. Common tests cover first-match ordering, removal and next-match selection, identity changes on upsert, newly inserted keys, and series/movie type compatibility.

The improvement removes repeated O(local watched items) fallback scans after the index is built; index construction is a single O(local watched items) pass performed only for a page containing a fallback DELETE. No persisted schema, UI, sync payload, dependencies or build configuration changed. Tests/builds/CI remain unrun as required.

## Verification record and limits

- Source reviewed through GitHub at the recorded branch/commit; upstream `enhanced` was checked for the relevant Trakt and Home source paths.
- Regression tests were added for duplicate episode coordinates, incomplete coordinates, collection source order, explicit-versus-legacy source precedence, and the six-item cap.
- **Tests, builds, static-analysis tasks, CI and performance benchmarks have not been run.** Do not mark them as passing. Run appropriate shared tests and Android/iOS verification only after explicit authorisation.
- Structural complexity expectations are reasoned from the source. There are no measured Nuvio runtime speedups yet.


## Additional audit batch - Anime4K frame-loop allocation reduction (8 November 2026)

The Android Anime4K GL shader path previously allocated an FBO ID array and texture-binding map for every video frame, then built a mapped input list and texture-ID/size pairs for each pass, plus a tap-offset Pair for the final copy pass. Those per-frame temporaries are now represented by per-program reusable state: one framebuffer array, one input binding, one reusable binding map, binding objects attached to configured targets, a cached copy-pass binding list, and scalar tap-offset values. The draw loop resolves existing pass bind names in original order, retaining target overwrite semantics (including MAIN), GL framebuffer selection, sampler units, dimensions, reciprocal texel sizes and the exact resample offsets.

Safety basis: upstream Media3 1.8.0 source BaseGlShaderProgram.java documents that all methods run on the GL-context-owning thread. DefaultVideoFrameProcessor.java creates a single-thread executor by default, and VideoFrameProcessingTaskExecutor.java wraps work on that single executor. Reusable mutable scratch state is therefore confined to the existing serial GL thread. No GL commands, shader source, pass planning, frame format, or UI code was changed.

Verification: source-level diff and call-path review only. No tests, Android/iOS build, runtime playback, static analysis, CI or performance benchmarks were run. The expected improvement is fewer temporary allocations in the active Anime4K per-frame path; no measured frame-time or GC reduction is claimed.

### Anime4K profile-sync scope reviewed

The new Android-host tests for Anime4K local settings exercise PlayerSettingsStorage.exportToSyncPayload/replaceFromSyncPayload. The Android/iOS platform implementations omit the Anime4K keys from profile-sync payloads, preserving device-local behaviour. The similarly named SettingsConfigTransfer utility is a separate manual settings/configuration transfer path, not the profile-sync implementation under test; its handling was left unchanged rather than conflating the two flows.
