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


### Debrid profile switching and Cloud Library cancellation

Android and iOS already persist Debrid preferences and provider API keys through `ProfileScopedKey`, and `DebridSettingsRepository` already exposes `onProfileChanged()`. The normal `ProfileRepository.selectProfile()` path omitted that hook, unlike the neighboring TMDB/MDBList/player settings repositories. The selected profile could therefore keep the previous profile's Debrid configuration in memory until a later reset/restart. The profile selector now invokes the existing Debrid reload hook; storage format, sync ownership and settings UI are unchanged.

Both Cloud Library provider adapters wrapped suspend `listItems()` network calls in Kotlin `runCatching`. Since `runCatching` catches `Throwable`, coroutine `CancellationException` was converted into `Result.failure`, allowing a cancelled provider request to be treated as an ordinary provider error and potentially reported/published. A shared `cloudLibraryListResult` wrapper now rethrows cancellation and converts other thrown failures to `Result.failure`, preserving the existing provider-result contract. Kotlin's official [coroutine cancellation guidance](https://kotlinlang.org/docs/coroutines-cancellation.html) explicitly says to rethrow cancellation exceptions; its [runCatching API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/run-catching.html) documents that all thrown `Throwable` values are captured.

No dependency, UI, persistence, resource, build or CI change is involved. Common tests cover cancellation propagation, ordinary failure conversion and successful-value preservation. The tests have not been executed because builds/tests remain unauthorised.

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


### Profile and supporter assets — concurrency and lifecycle hardening (8 November 2026)

The prior pass had identified that `ProfileBackgroundRepository` is a process-wide singleton called from Compose UI, while its catalog RPC and asset jobs run on `Dispatchers.Default`. Its mutable `remoteCatalog`, hydration/load flags, and `loadJob`/`assetLoadJob` references were read and written without a shared synchronization boundary. A profile/account invalidation could therefore race an older request completing, and replacing a preload could still allow work already in flight to publish after the newer request was active.

The repository now uses the project's existing multiplatform AtomicFU `SynchronizedObject` monitor to protect its compound state transitions and catalog publication. Network calls and job joins remain outside the monitor. Lazy job creation makes the job reference visible before work starts; replaced jobs are cancelled after leaving the critical section. An invalidation generation prevents a request from a prior cache/catalog lifecycle from publishing, while a monotonically increasing asset-request ID prevents a superseded selection/preload from publishing into the current catalog. The existing asset-version check remains in place as a second guard. Cached selected images are still published immediately as before; when a portrait asset has a configured path but no portrait cache entry, the cached landscape remains the temporary fallback while the portrait is fetched.

Both profile backgrounds and supporter avatars previously created one child coroutine per server-returned asset in a single `map { async { ... } }.awaitAll()` fan-out. The shared `mapInBatchesConcurrently` helper now creates at most four child jobs at a time, awaits that batch, then starts the next. It retains source ordering, propagates parent cancellation through structured concurrency, and bounds both in-flight asset work and the number of suspended child jobs. All catalog entries are still loaded; no item cap, ordering, selected-image priority, cache key, entitlement rule, persistence format, or UI was changed. This is a concurrency bound, not a byte-accurate cap on the final images retained in the catalog.

The avatar repository also had plain in-flight booleans accessed by screen refreshes and its long-lived membership observer. Those flags were replaced with coroutine `Mutex.tryLock()` single-flight gates, preserving the old "skip when already fetching" behaviour without racing the check/set. A shared AtomicFU monitor now protects catalog/entitlement/hydration/refresh metadata transitions and the combined standard/member cache-payload snapshot, preventing parallel standard and member refreshes from persisting mixed or lost catalog state. No suspending call is made while this monitor is held.

No dependencies, build scripts, Gradle settings, resources, CI workflows, signing, Android/iOS storage adapters, profile settings, website-owned configuration, Liquid Glass Editor, navigation, or visual assets were modified. The implementation reuses the already declared AtomicFU 0.32.1 and kotlinx.coroutines 1.10.2 APIs.

Research basis:
- Kotlin's [shared mutable state and concurrency guidance](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html) documents the races caused by shared mutable state on multi-threaded dispatchers.
- The official [kotlinx.coroutines Semaphore and synchronization APIs](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/) describe fair, cancellable concurrency limits; this implementation uses bounded batches so it also avoids creating a large queue of waiting child jobs.
- The official [AtomicFU multiplatform locks API](https://github.com/Kotlin/kotlinx-atomicfu/blob/master/_autodocs/api-reference/atomicfu-locks-synchronized.md) documents the cross-platform monitor used by the project's existing synchronized metadata cache.
- The Kotlin developer discussion [Best Practices for parallelizing different types of IO](https://discuss.kotlinlang.org/t/best-practices-for-parallelizing-different-types-of-io/24878) cautions that concurrency strategy should fit the workload rather than blindly assigning a dispatcher. The Stack Overflow discussion [Kotlin async/await with limited parallelism](https://stackoverflow.com/questions/71675069/kotlin-async-await-with-limited-parallelism) explains the distinction between dispatcher parallelism and task concurrency. A targeted Reddit search did not return a relevant, verifiable implementation discussion, so Reddit is not cited as evidence.

A common regression test covers the batch concurrency ceiling, source-order preservation, empty input, and cancellation before subsequent batches. The tests are added but have not been executed: running Gradle tests would start a build, which remains prohibited until explicit authorisation. Static review only; no runtime or performance measurement is claimed.


### Trakt library — profile ownership and stale refresh isolation (8 November 2026)

The Trakt library repository already had an `onProfileChanged()` lifecycle method, and its Android/iOS storage was profile-scoped, but `ProfileRepository.selectProfile()` never invoked that hook. Consequently the singleton's `hasLoaded` flag and in-memory `TraktLibraryUiState` could remain bound to the previous profile. A second race existed after a profile change: an old refresh could continue emitting partial states and persist its final payload after the active profile changed. The platform storage adapter derived the key from the *current* active profile at save time, so a response fetched with the previous profile's Trakt credentials could be written into the new profile's cache.

Profile selection now invokes the repository's existing reset/reload hook after tracking providers load their new profile credentials. The account cleaner also resets its in-memory state and invalidates outstanding refreshes; the repository increments a generation on profile changes and cleanup, and partial/final refresh publication, list-tab preloads, optimistic membership publication and rollback all verify the captured profile/generation before touching current UI state. Trakt API calls use the captured profile ID when obtaining authorization headers. The storage contract now takes an explicit profile index, so a late response can never use an implicitly current profile key. Cache payload shape and keys remain unchanged (`trakt_library_payload_<profileIndex>`); both platform adapters continue using the existing `ProfileScopedKey` contract.

The regression test exercises the same profile/generation predicate used by repository publication. This is source-level coverage only; it has not been executed because Gradle tests and all builds remain prohibited pending explicit authorisation.

### iOS downloads — identity-safe background job and completion registries (8 November 2026)

The iOS download adapter's process-wide subtitle-job map was accessed by the main/UI caller and jobs completing on `Dispatchers.Default`. Replacing a job cancelled the old one before publishing the new value; its completion callback could then remove the replacement, and the plain mutable map itself had no cross-thread synchronization. The background-session completion-handler map was likewise registered by app lifecycle code and removed by the URLSession delegate queue.

A common `IdentityKeyedRegistry` now uses the already-present kotlinx.coroutines 1.10.2 `MutableStateFlow.getAndUpdate` CAS primitive for atomic key replacement/removal. Subtitle jobs are created lazily, registered first, then their predecessor is cancelled; completion removes an entry only when the exact same Job is still registered. Background-session completion handlers use the same atomic registry. No new dependency or platform API was introduced, and the download transport, URLs, resume data, file handling, progress throttling, user-visible messages and UI remain unchanged.

Research basis:
- Apple's [URLSession documentation](https://developer.apple.com/documentation/foundation/urlsession.md) states that the session API is thread-safe and that delegate callbacks execute on the supplied delegate queue; the adapter supplies a serial queue. This confirms delegate callbacks are serial with one another, but does not serialize the separate main-thread access to the top-level job/completion registries, hence the atomic registry.
- Kotlin's official [MutableStateFlow getAndUpdate API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/get-and-update.html) provides an atomic update-and-return-old-value operation. Its updater may be re-evaluated under contention, so the registry updater is pure and has no side effects.
- The existing Trakt platform storage adapters and [ProfileScopedKey](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/blob/main-build-testing/composeApp/src/commonMain/kotlin/com/nuvio/app/core/storage/ProfileScopedKey.kt) were inspected directly; explicit profile IDs preserve the existing persisted key format.

### Investigated and deliberately left for a separate iOS lifecycle pass

The iOS `IosBackgroundDownloadCoordinatorImpl` also holds mutable task/active-download/pending-pause maps. Its URLSession delegate callbacks are serialized by the explicitly configured one-operation delegate queue, but app-initiated start/cancel operations can arrive on other threads. Safely consolidating that entire task state machine requires coordinated start reservations, cancellation ordering and resume-data handling; this pass does not make an incomplete lock-only change that could block the UI during file finalisation or alter pause/resume semantics. This remains a verified follow-up rather than a claim of complete iOS coordinator thread-safety.

Verification for this batch: exact call sites, profile-scoped Android/iOS storage adapters, source imports and existing dependency versions were reviewed. Static consistency checks only; no Gradle test, Kotlin compile, Android/iOS build, Xcode task or CI workflow was started.


### Profile switching — pinned stream sources and Trakt comments setting state (8 November 2026)

A follow-up call-site audit compared common repositories with the profile-selection lifecycle. Two active profile-scoped repositories exposed their own `onProfileChanged()` hooks but were not wired into `ProfileRepository.selectProfile()`:

- `PinnedStreamSourcesRepository` is consumed by both the stream filter row and stream cards. Its platform storage uses `ProfileScopedKey`, but its process-wide `hasLoaded` flag and StateFlow retained the previous profile's pinned source IDs after a switch. The Stream Badge settings repository reload does not reload this separate state holder.
- `TraktCommentsSettings` is loaded by `MetaDetailsScreen` and its Android/iOS storage keys are profile-scoped. It also had an existing reload hook that profile selection did not invoke, so the comments enabled/disabled preference could carry over from the prior profile in the running process.

Profile selection now invokes both existing hooks alongside their owning feature lifecycles. Both repositories also expose `clearLocalState()`, and the account cleaner resets their in-memory state after the existing platform preference wipe. The Android wipe already clears the full `nuvio_stream_badge_settings` and Trakt-comments preference files; the iOS key inventory already removes `pinned_stream_sources` and `comments_enabled`. No storage keys, sync payloads, visual behaviour, or settings ownership were changed.

The similarly named `TraktSettingsRepository` was reviewed but not wired: no production call site was established in the inspected feature paths, and the active progress/library settings are owned by `TrackingSettingsRepository`. It was left unchanged rather than reviving a potentially legacy path.

Verification: direct consumers in `StreamsScreen`, `ProviderFilterRow`, and `MetaDetailsScreen` were inspected; the Android/iOS storage adapters and account-cleaner key coverage were checked. Static source/call-site review only; tests, compilation, builds and CI remain unrun by instruction.


### Trakt comments — preserve coroutine cancellation in optional ID lookup (8 November 2026)

The TMDB-to-Trakt fallback lookup in `TraktCommentsRepository.resolveViaTraktSearch` caught every `Exception` and converted it to a nullable “no result”. Coroutine `CancellationException` is an `Exception`, so closing/navigating away from a details request while this lookup was suspended could be swallowed as an ordinary failed lookup. That breaks structured cancellation and can allow the parent request to continue into its empty-page path instead of stopping.

The fallback now uses a small internal `bestEffortTraktCommentsLookup` helper: ordinary lookup errors are reported through the existing logger and still become `null`, but cancellation is rethrown unchanged. The HTTP call, target-resolution order, result parsing, log message, fallback UI and comment cache are otherwise unchanged. Three common tests cover cancellation identity/propagation, ordinary failure conversion/log callback, and success preservation.

Research basis: Kotlin's official [cancellation and timeouts guide](https://kotlinlang.org/docs/cancellation-and-timeouts.html) says catching `CancellationException` without rethrowing breaks cancellation propagation and explicitly directs callers to rethrow it. The code path was traced from the active `MetaDetailsScreen` comments loaders to `resolveViaTraktSearch`; cancellation from the surrounding `getCommentsPage` request was already rethrown by its outer transport catch, so the nested fallback catch was the specific suppression point.

Static source checks only. The common regression tests were added but not executed because test/build execution remains prohibited until explicit authorisation.


### Cancellation and structured concurrency — shared result helper, metadata and release notifications (8 November 2026)

A source audit found the same cancellation hazard in additional suspend paths. `MetaDetailsRepository.applyMoreLikeThisSource()` wrapped Simkl and Trakt related-title requests in `runCatching`, converting `CancellationException` into an empty provider result. The episode-release notification flow did the same for permission, scheduling, test-notification and per-show metadata calls. Its per-show fan-out also used `scope.async` on the repository's long-lived `SupervisorJob` scope rather than a lexical child scope, so cancelling a refresh did not cancel its outstanding metadata fetches.

A single internal `core.concurrency.runCatchingUnlessCancelled` helper now captures ordinary failures as `Result.failure` while always rethrowing `CancellationException`. The existing Streams helper delegates to this shared implementation to preserve its current package-level call shape. Meta Details and Episode Release Notifications use the shared implementation directly. The notification refresh fan-out now uses `coroutineScope { async { ... } }`, with stable snapshots of the tracked-show and cached-request maps for that refresh, so child work is cancelled with its owner and cannot mix map generations during iteration.

Ordinary provider and platform failures retain their previous fallback values, log messages and UI result handling. No schedule times, request IDs, metadata precedence, permissions, platform APIs, settings, persistence keys, UI, resources, dependencies or build configuration were changed.

Research basis: Kotlin's official [cancellation and timeouts guide](https://kotlinlang.org/docs/cancellation-and-timeouts.html) explicitly warns that `runCatching` catches `CancellationException` and requires rethrowing cancellation. The Kotlinx Coroutines 1.10.1 documentation (closest indexed Context7 version to this branch's 1.10.2) describes parent-child cancellation for `coroutineScope` and `async`; the project's installed kotlinx.coroutines version is 1.10.2 and these APIs are already used in its common source. A relevant developer discussion in [kotlin-result issue #64](https://github.com/michaelbull/kotlin-result/issues/64) documents the same `runCatching` cancellation trap. No Reddit thread with a more authoritative or directly applicable example was found, so the implementation is based on the official coroutine contract rather than social-media advice.

A common test file covers cancellation identity/propagation, ordinary failure capture and successful values for the shared helper. Tests were authored but not executed, and no builds or CI were started, per instruction. Static source checks only.

### Release notifications — profile isolation and account-wipe notification cleanup (8 November 2026)

The earlier audit established that the notification singleton had no profile/generation token: `onProfileChanged()` could reload profile-scoped preferences while an old refresh was active, `buildRequestsForShow()` read the active profile only when constructing request IDs, and storage/platform adapters derived keys from the active profile at save time. Account cleanup also removed local preferences but left OS-pending and already-delivered episode-release notifications in place.

This continuation resolves those paths:

- Each notification load/refresh now carries an immutable profile ID and generation token. State reconciliation, cached requests, UI publication, persistence and platform scheduling all reject stale generations. Metadata requests build IDs from the captured profile ID, never a later read of the active profile.
- The storage expect/actual contract accepts an explicit profile ID on both Android and iOS. `ProfileScopedKey.of(baseKey, profileId)` preserves the existing `<base>_<profileIndex>` storage format; no payload migration is needed.
- Platform notification scheduling and profile clearing receive that explicit profile ID. A shared platform-operation mutex serializes schedule/test/clear operations with account-wide cleanup, so an already-running schedule finishes before the account wipe removes the feature's OS notifications, while queued stale work is rejected.
- Account cleanup invalidates the notification generation before clearing OS queues and before wiping platform preferences. It removes only episode-release notifications, not unrelated app notifications. On Android it clears all profile schedule keys, delivered-ID bookkeeping, the feature's active notifications, the feature alarm, the notification backstop work and legacy work. New notifications use a dedicated tag; cleanup also recognises the existing dedicated channel and legacy untagged IDs when their request IDs are still recorded. On iOS it enumerates pending and delivered notifications and removes only identifiers beginning with the feature's existing `episode-release-` prefix, clears profile schedule-ID records and removes the feature's temporary attachment directory.
- The account-cleanup gate prevents the singleton from reloading notification settings before account cleanup has completed. Android requires its notification platform context to be initialized and checks the synchronous SharedPreferences commit; iOS checks temporary attachment-directory removal. Failures propagate so the caller does not silently continue into the local storage wipe.
- The notification refresh now rechecks the current enabled/profile/cleanup state under the serialized platform-operation boundary immediately before scheduling. Disabling the setting publishes and persists the disabled state before clearing the profile queue, preventing an older metadata refresh from re-adding notifications after the user switches the feature off. The same current-state check protects the final scheduled-count UI update.

Compatibility and limits: Android's `NotificationManager.getActiveNotifications()` is available from API 23 and this app's minSdk is 24. API 26+ cleanup can identify legacy notifications by the dedicated channel; all newly posted notifications are tagged, including on API 24–25. A very old untagged test notification on API 24–25 that was never recorded in the existing delivered-ID set cannot be identified reliably without risking unrelated app notifications, so it is intentionally not removed by guessing. iOS uses the documented iOS 10+ pending/delivered notification APIs; the iOS targets in this project are newer than that API floor.

Research basis:
- Apple's official [UNUserNotificationCenter documentation](https://developer.apple.com/documentation/usernotifications/unusernotificationcenter.md) documents separate enumeration and targeted removal of pending and delivered notifications.
- The Apple SDK Objective-C declaration [UNUserNotificationCenter.h](https://github.com/xybp888/iOS-SDKs/blob/master/iPhoneOS13.0.sdk/System/Library/Frameworks/UserNotifications.framework/Headers/UNUserNotificationCenter.h) confirms the Kotlin/Native selector `removeDeliveredNotificationsWithIdentifiers`.
- Android's official [NotificationManager API](https://developer.android.com/reference/android/app/NotificationManager) documents active notifications with tag/ID and targeted cancellation. The [AlarmManager API](https://developer.android.com/reference/android/app/AlarmManager) documents cancellation by the matching PendingIntent.
- The current code's `ProfileScopedKey`, Android preference naming and iOS profile-suffixed keys were inspected directly; the explicit profile argument preserves the same key spelling.
- Context7 confirmed the existing AtomicFU multiplatform synchronization APIs already used elsewhere in this codebase; no new dependency or concurrency primitive was introduced.

A common regression test file covers the profile/generation predicate and the exact notification-identifier prefix used by account cleanup. It was authored but not run. Static source and API checks only: no Kotlin compile, Android/iOS build, Gradle test, Xcode task or CI workflow was executed. No UI, resources, settings ownership, website configuration, notification timings, payload fields, dependency versions or build configuration were changed.



### iOS background downloads — verify resumed-segment I/O and release failed task handles (8 November 2026)

A focused review of the iOS background-download adapter found two independent failure-path defects in `DownloadsPlatformDownloader.ios.kt`:

- `copyByAppending()` previously treated any non-positive `fread()` result as normal EOF, ignored the byte count returned by `fwrite()`, and ignored both `fclose()` results. A short read caused by an I/O error or a short write could therefore be mistaken for a complete copy. Because `appendFile()` uses this result to decide whether to rename the partial file into its final destination and report download success, this could promote incomplete data to a completed download. Buffered output failures can also be reported only when the stream is flushed/closed.
- `finishWithFailure()` removed the active-download entry but left the corresponding `NSURLSessionDownloadTask` in `tasksByDownloadId`. When a failure originates from `didFinishDownloadingToURL`, the subsequent completion delegate callback can no longer pass `activeFor(task)`, because the active entry has already been removed. The task map then retains the obsolete task reference.

The append routine now distinguishes a clean EOF from an input-stream error using `feof()` and `ferror()`, rejects incomplete `fwrite()` counts and output-stream errors, and includes successful closure of both streams in the copy result. The temporary response file is removed only after all read, write and close checks succeed. `finishWithFailure()` now removes the task-map entry as well as the active-download entry before invoking the failure callback.

The API surface uses the existing Kotlin/Native `platform.posix` library; its iOS platform definition includes `stdio.h`. No dependency, source-set, build setting, download URL, HTTP behavior, resume format, persistence key, UI, notification, or Android implementation was changed. The broader `IosBackgroundDownloadCoordinatorImpl` cross-thread map/state-machine issue remains intentionally deferred as recorded above; this change does not claim to make those maps thread-safe.

Research basis:
- Apple's [iOS fread/fwrite manual](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man3/fread.3.html) documents short item counts on stream errors/EOF and directs callers to `feof()`/`ferror()` to distinguish them.
- Apple's [iOS feof/ferror manual](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man3/feof.3.html) documents the stream EOF and error indicators.
- The Open Group [POSIX fwrite specification](https://pubs.opengroup.org/onlinepubs/9799919799.2024edition/functions/fwrite.html) defines the returned count as the number of elements successfully written.
- The Open Group [POSIX fclose specification](https://pubs.opengroup.org/onlinepubs/9699919799/functions/fclose.html) specifies that close flushes buffered output and reports failure when flushing/closing fails.
- JetBrains' [Kotlin/Native C interop documentation](https://kotlinlang.org/docs/native-c-interop.html) confirms platform libraries provide POSIX bindings; the current Kotlin Native iOS [posix.def](https://github.com/JetBrains/kotlin/blob/master/kotlin-native/platformLibs/src/platform/ios/posix.def) includes `stdio.h`.
- A relevant practitioner discussion, [fclose return value check](https://stackoverflow.com/questions/1954273/fclose-return-value-check), describes delayed write failures being reported at close and the data-loss risk from ignoring that result. No directly applicable Reddit discussion was found, so Reddit is not cited as evidence.

Verification limits: inspected the complete edited iOS adapter, the `appendFile()` caller and its success/failure path, all `finishWithFailure()` call sites in the file, existing iOS POSIX imports, the Kotlin/Native platform binding definition, and the branch's version catalog. GitHub's resulting commit diff and a fresh branch/file fetch were checked; exact source guards for EOF, input errors, short writes, output errors, both close results, and task-map cleanup were confirmed. No regression test was added or run in this restricted pass, and no Kotlin compile, Gradle task, Xcode task, build, CI workflow, or build-triggering check was started, as required. These changes therefore remain statically reviewed but not compiler- or runtime-verified.



### Android resumable downloads — validate exact Content-Range payloads and restore partial sinks (8 November 2026)

A source-level audit of the Android download transfer uncovered a data-integrity gap in `AndroidDownloadTransfer.kt`. The existing parser retained only the first byte and complete representation length from `Content-Range`, discarding the inclusive last-byte position. The transfer therefore checked that a 206 response began at the requested offset and that the final accumulated count equalled the inferred/declared file total, but it did not prove that the response body actually contained the byte interval declared by the response.

For example, with an existing 5-byte partial file, a response declaring `Content-Range: bytes 5-6/11` but carrying a 6-byte body could previously be accepted: the accumulated count became 11 and matched the advertised total, even though the header only described a 2-byte range.

The Android-only fix makes these checks explicit:

- `DownloadContentRange` now retains the first byte, inclusive last byte and optional complete length. The parser rejects reversed ranges, inclusive-length overflow, out-of-range complete lengths and numeric complete-length values that overflow `Long` instead of treating an invalid numeric total as the unknown-length `*` form.
- When OkHttp exposes a response-body length, a 206 payload whose `Content-Length` differs from the inclusive range length is rejected before opening the sink.
- For unknown-length/chunked bodies, the transfer counts actual bytes against the declared range. It stops reading once the body exceeds the declared interval and, on a short or oversized clean response, truncates the sink back to the original offset before returning an error. The rollback is synced to storage.
- The internal `DownloadSink` contract now supports truncation for both app-private files (`RandomAccessFile.setLength`) and Storage Access Framework documents (`FileChannel.truncate`). Both implementations refuse to extend a sink during rollback; this prevents accidentally manufacturing zero-filled bytes if the file is already shorter than the captured offset.
- An invalid range is a permanent protocol error rather than a transient network failure, so the scheduler does not repeatedly download the same malformed response. If rollback reduces the byte count, the throttled progress reporter now publishes that decrease immediately, keeping persisted/UI progress aligned with the actual partial file.
- Range validation/rollback runs before the final cancellation check, so cancellation arriving immediately after the response body closes cannot bypass integrity repair.

The existing 416 restart, normal 200 fallback, strong-ETag/Last-Modified request handling, progress cadence, download location choice, retry limits for transient I/O, and completion path remain otherwise unchanged. No dependency, API endpoint, persistence format, resource, user setting, UI, build configuration, or iOS source was changed.

Regression coverage was added in `AndroidDownloadTransferTest.kt` for inclusive range byte counts and overflowed totals, known-length header/body mismatch, unknown-length chunked mismatch with prefix rollback, and non-retry classification of invalid ranges. The already-existing valid resume, ignored-range restart, 416 retry, truncated-body and cancellation tests were retained.

Research basis:
- The IETF [RFC 9110 §14.1.2 and §14.4](https://www.rfc-editor.org/rfc/rfc9110.html) defines the last byte of a byte range as inclusive, specifies that `Content-Range` identifies the returned range, and states that a numeric complete length must be greater than the last byte. It also allows a server to return only portions of requested ranges and expects the client to request remaining portions where appropriate.
- The IETF [RFC 9110 §15.3.7](https://www.rfc-editor.org/rfc/rfc9110.html) defines 206 Partial Content as carrying one or more partial representations; this is why the payload must be checked against the exact range rather than only the overall file total.
- The project's Android source uses OkHttp 4.12.0, whose response-body length can be unknown; the transfer therefore checks the actual byte count when `contentLength()` is unavailable. The project test dependency is MockWebServer 5.3.2, whose official [MockResponse.setChunkedBody API](https://square.github.io/okhttp/5.x/mockwebserver/okhttp3.mockwebserver/-mock-response/set-chunked-body.html) is used for the chunked-response regression case.
- Oracle's [Java 17 FileChannel documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html) specifies that `truncate(size)` discards bytes beyond the new end and does not extend a shorter file. The [RandomAccessFile documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/RandomAccessFile.html) and [FileDescriptor.sync documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/io/FileDescriptor.html) support the private-file rollback and persistence calls. These JDK APIs are also part of the Android Java I/O surface used by this Android target.
- The repository's `AndroidDownloadExport.sink()` was inspected and confirmed to route persisted document-URI partials through `DocumentDownloadSink`, so both the private and SAF rollback paths are covered by the shared sink contract.

### Android range-resume follow-up deliberately left open

A separate standards edge was identified but not changed in this batch: a 206 response whose `Content-Range` uses an unknown complete length (`*`) can represent only one portion of an open-ended range, with the remaining bytes requiring another Range request. The existing transfer still derives its completion total from the response-body length in this case, and therefore cannot prove that an unknown-total segment was the final segment. Correctly supporting this requires a bounded multi-response continuation state machine and explicit treatment of the terminating 416 response, validator retention, and sources that do not provide a validator. Simply setting the total to null would still allow the current function to return success after one partial segment; simply rejecting every `*` response would regress sources that use unknown-length ranges. Leave this path unchanged until that state-machine change can be separately designed and covered, rather than claiming unknown-total range completion is fixed.

Verification limits: reviewed the four complete changed Android source/test files, the SAF export adapter, the Android scheduler's worker retry and progress path, the current version catalog/build dependency declarations, and the exact branch commit diffs. GitHub re-fetches and static source checks confirmed both sink implementations provide rollback, the parser retains all range fields, content-length and streamed-byte guards are present, invalid ranges are non-retryable, and progress can move backwards after rollback. Regression tests were authored but not run: executing Gradle tests would start a build, which remains prohibited until explicit authorisation. No Kotlin compilation, Android build, Xcode build, CI workflow or build-triggering check was started. The implementation therefore remains statically reviewed, not compiler- or runtime-verified.


### Shared HTTP range parsing and iOS resumed-download integrity (8 November 2026)

The Android range audit exposed a second, platform-specific gap in the iOS NSURLSession download coordinator. In didFinishDownloadingToURL, the previous implementation treated any HTTP 206 response as an appendable segment when a request had a positive Range offset. It did not inspect the response's Content-Range header or compare the temporary file's actual length with the returned byte interval. A response for the wrong offset, a short/oversized segment, an unknown-total segment, or a non-final partial range could therefore be appended and promoted as a completed file.

The following changes apply the same range contract on both platforms:

- Moved DownloadContentRange and parseDownloadContentRange() into commonMain, so Android and iOS use one parser for inclusive first/last offsets, known/unknown complete length, numeric overflow, reversed ranges and total-boundary validation.
- Added common tests for inclusive byte counts, unknown total parsing, reversed/out-of-bounds ranges and overflow. The Android host test no longer carries a duplicate parser-only test; it retains its HTTP transfer and rollback regression coverage.
- The iOS download delegate now reads Content-Range through NSHTTPURLResponse.valueForHTTPHeaderField() and, before touching the saved partial, requires the returned first byte and existing partial file length to equal the actual requested offset. It also checks the temporary URLSession response file length against the declared inclusive byte interval.
- For iOS 206 responses, the range must have a known complete length and must reach the end of that representation (lastByteInclusive + 1 equals completeLength). Unknown-total and non-final subranges are rejected before append rather than being misreported as complete. This implementation intentionally does not chain multiple partial iOS responses; doing so safely across app restarts requires validator ownership/persistence and a separate continuation state machine.
- Ranged iOS requests explicitly set Accept-Encoding: identity, after applying source headers, so a resumed byte offset is not silently interpreted against encoded bytes while URLSession exposes transparently decoded bytes. Non-ranged requests keep their existing content-encoding behavior.
- If an append fails after partially writing, the adapter now attempts to truncate the .part file back to the captured prefix length. Apple documents that POSIX truncate() can also extend a shorter file with zero-filled bytes, so the rollback helper first checks the current file length and refuses to extend it. If the saved prefix cannot be restored, the partial file is removed so a later retry cannot resume from a corrupted offset.

The normal 200 response path still replaces the partial with the full response; the existing 416 restart behavior, background URLSession identifiers, download filenames, subtitle preparation, UI callbacks, storage locations, permissions, settings, notification behavior and account/profile state are unchanged. No dependency, resource, build setting, Xcode project setting or Android platform implementation was added or changed in this iOS follow-up.

Research basis:
- The IETF RFC 9110 sections 14.1.2 and 14.4 define byte ranges as inclusive offsets and specify that byte ranges are calculated against the encoded representation when content coding is applied. They also distinguish a known complete length from the * unknown-length form.
- Apple's HTTPURLResponse.value(forHTTPHeaderField:) provides a case-insensitive header lookup. The method is available from iOS 13; this project currently sets IPHONEOS_DEPLOYMENT_TARGET = 16.1.
- Apple's NSURLSessionDownloadDelegate callback documentation requires the temporary downloaded file to be moved or read before the delegate callback returns; the response-size check and append remain inside that callback.
- Apple's iOS POSIX truncate(2) manual specifies that truncate() both truncates and extends files, and returns 0 on success. The helper explicitly guards against the extension case.
- JetBrains' Kotlin Multiplatform platform-library documentation confirms Foundation and POSIX are Kotlin/Native platform libraries; the iOS POSIX definition includes unistd.h. No custom cinterop configuration or new dependency is required.
- The project already used valueForHTTPHeaderField for NSURLRequest and the same shared range parser on Android; the iOS API call uses the already imported Foundation selector. The current iOS deployment target is above the API's availability floor.
- Developer discussions and implementation examples were used to identify URLSession transparent gzip handling as a range-resume hazard; the actual behavior change is grounded in the RFC's encoded-byte range semantics and the repository's use of a byte-addressed partial file. No directly applicable Reddit discussion supplied stronger evidence, so none is cited.

### iOS range-resume limitations deliberately retained

This iOS pass fails safely for 206 responses whose complete length is unknown (*) or whose range stops before the known representation end. It does not yet implement multiple sequential partial-response requests. Supporting those cases safely requires a persistent strong ETag/Last-Modified validator for the partial file, If-Range handling across process death, and explicit end-of-file behavior; silently chaining ranges without that ownership would risk combining bytes from different versions of a source. The Android unknown-total range-completion follow-up recorded above also remains open.

Verification limits: inspected the complete iOS download adapter, all appendFile() and buildRequest() call sites in that file, the Android transfer/parser implementation, the new common parser/tests, current iOS deployment target and Kotlin Multiplatform source-set configuration. GitHub re-fetches and diffs were used to verify the shared parser is now the only parser definition, both platforms call it, the iOS 206 path validates status/header/offset/prefix/segment/final-range length before promotion, ranged requests set identity encoding, and append rollback cannot extend a shorter file. Common parser and Android regression tests were authored but not run; there is no dedicated iOS download-delegate integration test in the current repository. No Gradle test, Kotlin compile, Android/iOS build, Xcode task, CI workflow or build-triggering command was started. The new code is statically reviewed but not compiler- or runtime-verified.


### HTTP range-unit token matching — case-insensitive parser correction (8 November 2026)

A final standards check found that the shared Content-Range parser still matched the registered range unit with a case-sensitive regular expression. RFC 9110 §14.1 explicitly defines all range-unit names as case-insensitive. The common parser now uses Kotlin's standard RegexOption.IGNORE_CASE, and the common regression suite includes an uppercase BYTES example. This is a parser-only correction; no request behavior, dependencies or platform code changed.

Research basis: [RFC 9110 §14.1](https://www.rfc-editor.org/rfc/rfc9110.html) states that all range unit names are case-insensitive. An aiohttp issue was found documenting the same case-sensitive-regex failure mode in a separate implementation; it was used only as corroborating implementation evidence, not as the normative basis.

Verification limits: the updated shared parser and regression case were re-fetched from GitHub and inspected. Tests were not executed because Gradle test execution is prohibited until explicit authorisation.



### P2P — surface late command-scoped torrent errors on Android and full iOS (8 November 2026)

A source-level event-contract review found a real playback-failure reporting gap. The app previously treated only TorrentError events with requestId == 0 as unsolicited failures. That assumption is not valid for the native Nuvio Engine: backend-originated torrent events retain the originating command request ID, and the engine runtime forwards both request_id and torrent_id into the public event unchanged. Android's 0.1.6 Kotlin wrapper emits every event to its public events Flow before looking up a pending command; once addMagnet has completed and removed its pending entry, a later error with the original non-zero request ID is still delivered to app collectors. The app's requestId == 0 filter discarded that later failure even when its torrent ID identified the active playback.

The native C++ source confirms the propagation path:
- `src/torrent/libtorrent_backend.cpp` queues torrent errors with their originating request ID.
- `src/core/engine_runtime.cpp` forwards backend request ID, torrent ID and message into `nuvio_engine_event`.
- The Android wrapper `platform/android/engine/src/main/kotlin/com/nuvio/engine/NuvioEngine.kt` emits events to `mutableEvents` before it checks whether a request is still pending.
- NuvioTV issue #4222 includes real playback logs with TorrentError requestId=1 and the active torrent hash after playback began. This is corroborating real-world evidence; the mobile branch itself was independently confirmed to have the same invalid requestId==0-only classifier.

Changes:
- Moved active-torrent error classification into commonMain. An error is now considered playback-fatal only when the event carries a non-empty torrent ID equal to the currently active torrent ID. This deliberately does not treat global cache-pressure events with no torrent ID as a fatal error for an arbitrary active torrent, and still rejects events for a previous/different torrent.
- Android records the current torrent ID as soon as the add command has completed and before prepareStream begins. Association is guarded by the current stream generation, so a stop/replacement that races startup cannot attach a stale torrent. The event collector surfaces matching non-zero and zero request-ID errors only while the matching stream is Connecting or Streaming; subsequent duplicate errors do not repeatedly mutate terminal state.
- The full iOS native implementation now associates the torrent before preparing the stream, checks active torrent identity while awaiting commands, and drains the native event queue from the existing 250 ms playback-stats loop. This means a late torrent failure is surfaced while playback is active instead of remaining queued until some later native command happens to poll it. No additional background job, dispatcher, dependency or timer was introduced.
- The iOS App Store P2P-unavailable stub is unchanged.
- The active iOS event-drain call is inside the stats poller's existing try/catch. A native event-queue polling failure is therefore converted to the same terminal playback error as a stream-stats read failure, rather than escaping the supervised polling job.

Regression coverage: added common tests for a matching active torrent error, late command-scoped error identity, mismatched/unknown torrent IDs, and blank-message fallback. Removed Android-only tests that encoded the incorrect rule that every non-zero request ID must be ignored. The common tests are authored but not executed.

Research and rejected approaches:
- The exact app dependency is `lib-nuvio-engine-android-0.1.6.aar`; the corresponding public engine source reports version 0.1.6. Inspection used the actual engine C/Kotlin source, not generic coroutine assumptions.
- Ignoring all non-zero request IDs was rejected because request IDs identify the originating command, not whether an error is still relevant to the current playback.
- Matching by request ID alone was also rejected because the completed add request has already left the Android wrapper's pending map. Matching by torrent ID preserves correlation with the active stream and rejects stale errors for other torrents.
- The separate low-storage issue in NuvioTV #4222 where the native engine retains active torrent payload bytes under the app cache directory is an engine storage-layer problem. Moving the directory or recycling a live torrent in the mobile app would not bound the active payload safely; no such workaround was added.
- The same NuvioTV report describes DataStore ENOSPC crashes, but no equivalent mobile watch-progress persistence path was proven in this branch during this pass. No storage/persistence behavior was changed on that basis.

Verification limits: re-fetched and statically reviewed the complete changed common P2P model file, Android P2P engine and host test, full iOS P2P engine, and cumulative notes. Checked that only a torrent ID equal to the active torrent can promote the event, Android association is generation-guarded and precedes prepareStream, and iOS event draining runs only within the already-owned active playback polling loop (which is cancelled/joined before stop/start operations acquire the native lifecycle mutex). No Gradle test, Kotlin compile, Android build, Xcode build, CI workflow or other build-triggering process was started. The implementation remains statically reviewed, not compiler- or runtime-verified.



### Watch progress persistence — keep write ordering profile-scoped (8 November 2026)

A targeted lifecycle review found that WatchProgressRepository kept pending snapshots in a map keyed by profile ID, but used one global `persistedSequence` watermark to decide whether a snapshot was stale. Each persist launches independently on `syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`; JSON encoding happens before the short synchronized write section. The shared Default dispatcher can run those jobs on a pool of threads, so their order of reaching the lock is not guaranteed to match the monotonically assigned sequence order.

This creates a concrete cross-profile loss case: profile A queues sequence 1, the user switches profiles, profile B queues sequence 2, and B's worker reaches the lock first. The old global watermark becomes 2; A's still-needed sequence 1 is then rejected even though no newer snapshot for profile A was stored. Its pending snapshot remains in the map, but `flushPendingWrites()` also compares against the same global watermark and can skip it before clearing the pending map.

The fix is deliberately limited to sequence bookkeeping:
- New `WatchProgressPersistSequence` tracks the latest accepted write separately per profile.
- A successful storage write advances only that profile's watermark. A later sequence for one profile no longer suppresses a pending write for another profile.
- The existing global `persistSequence` remains the source of monotonic ordering and account-clear invalidation. `clearLocalState()` records a global invalidation boundary and clears per-profile watermarks, so previously queued writes from a signed-out account remain stale.
- Both the asynchronous `persist()` worker and `flushPendingWrites()` use the same profile-scoped decision. The watermark advances only after the storage adapter call returns, leaving the pending snapshot eligible for retry if that call throws synchronously.
- No storage format, platform adapter, dispatcher, write cadence, UI, settings or account/profile ownership model changed.

Evidence:
- The actual repository launches persistence workers on `Dispatchers.Default`, and the actual pending queue is keyed by profile ID while the previous completion watermark was scalar.
- `loadFromDisk(profileId)` restores pending state for the requested profile without flushing other profiles; `onProfileChanged(profileId)` calls it directly. Thus multiple profiles can have outstanding snapshots concurrently.
- JetBrains' [Coroutine context and dispatchers](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html) documentation states that `Dispatchers.Default` uses a shared background thread pool. The [shared mutable state and concurrency](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html) guide explains that multi-threaded dispatchers can execute work concurrently and that synchronization protects state but does not turn independent launches into a FIFO work queue.
- The current upstream Nuvio Mobile implementation was also inspected and contains the same scalar watermark/map-key mismatch; this is a Mobile Enhanced correction rather than a port of an upstream fix.

Regression coverage: four common tests cover cross-profile independence, same-profile stale suppression, monotonic watermark behaviour and global account-clear invalidation. They are authored but not executed.

Rejected alternatives: serializing the entire persistence scope onto a single-thread dispatcher or adding a channel/actor would introduce broader scheduling and lifecycle changes. The existing synchronized critical section already serializes writes; the bug is the wrong key for the ordering watermark, so a per-profile watermark is the smaller correction.

Verification limits: the complete repository persistence section, profile-switch call path, Android SharedPreferences adapter, iOS NSUserDefaults adapter, current coroutine dependency and common-test source set were inspected. Static review confirms every remaining persisted watermark decision now includes profile identity, and the account-clear invalidation fence remains global. No Gradle test, Kotlin compilation, Android/iOS build, Xcode task or CI workflow was started. Compiler/runtime verification remains outstanding.


### iOS downloads — delegate-queue serialization does not protect profile state (8 November 2026)

A deeper lifecycle review found two related defects in the iOS download path that should be solved together, rather than patched independently.

The iOS NSURLSession delegate is created with a serial NSOperationQueue (maxConcurrentOperationCount = 1). Apple documents that URLSession delegate callbacks are scheduled on the configured delegate queue. This serializes callbacks from that session, but it does not serialize them with UI/profile actions or other code running outside that queue. The delegate calls the common repository callbacks directly. DownloadsRepository mutates a shared MutableStateFlow<DownloadsUiState> by reading _uiState.value, transforming a list, and then assigning a newly built state; its activeHandles map and synchronous persistence side effects are also not coordinated as one transaction. Kotlin documents that individual StateFlow methods are thread-safe, while atomic read-modify-write requires an operation such as update; a thread-safe getter and setter do not make the intervening calculation atomic.

A reproducible profile-switch path is visible in the current source:
- ProfileRepository.selectProfile() calls DownloadsRepository.onProfileChanged().
- DownloadsRepository.onProfileChanged() reloads the current profile through loadFromDisk() without cancelling the prior profile's active handles.
- The iOS DownloadsPlatformDownloader.restoreItem() normalizes persisted Downloading items to Paused.
- If a user switches away from a profile and returns while its in-process background task is still active, the item can be normalized to Paused despite the corresponding active handle/task still existing. The common progress/success callbacks update only items whose current status is Downloading; after normalization they leave the item unchanged. A successful delegate callback can remove the active handle while the repository item remains Paused, even though the iOS delegate has finalized the file. If the task completes while another profile is selected, the callback also targets the currently loaded list rather than a profile-keyed model, so the completion is not applied to the inactive profile's persisted item.
- This is separate from process-relaunch recovery. The code paths and Swift entry points responsible for reattaching background-session tasks after process relaunch were not fully established in this inspection, so no claim is made that relaunch reattachment is absent or broken.

The concurrency concern is broader than just changing _uiState.value = ... to _uiState.update { ... }. StateFlow's atomic update would protect the list calculation, but it would not by itself coordinate activeHandles, profile identity, platform task cancellation, file finalization, notification side effects, or ordered persistence. The iOS delegate queue is serial only for delegate callbacks; app-initiated start, pause, cancel, profile-switch and settings operations can still interleave with it.

Research and rejected quick fixes:
- Apple Foundation's URLSession documentation states that delegate work is scheduled on the configured delegate queue and that URLSession itself is thread-safe. That guarantee concerns URLSession operations and callback scheduling, not the application's unrelated repository maps or active-profile state.
- Kotlin's official StateFlow API describes all methods as thread-safe and provides atomic update/compareAndSet; the coroutine implementation uses a CAS retry loop. This distinguishes safe individual value access from safe compound repository mutation.
- Dispatching only the delegate callbacks to the main queue would reduce callback/UI data races but would not preserve completions for a profile that is no longer the active in-memory profile.
- Adding a lock only around activeDownloads and tasksByDownloadId would leave the repository's profile ownership and completion persistence unresolved. Holding such a lock across file I/O or cancellation/resume-data callbacks could also block unrelated work and introduce ordering hazards.
- Treating an existing destination file as proof of completion is unsafe without additional generation/validator checks: retries and resume operations reuse the destination filename, and stale destination files must not be mistaken for the current transfer.
- The appropriate future design is a single serialized download-state owner with profile-keyed transfer records and explicit task-generation identity. Delegate events should be reduced into the owning record regardless of which profile is visible; persistence should target that record's profile; stale task callbacks must not mutate a newer generation. The design needs deterministic tests for progress racing pause/cancel, profile A -> B -> A while a task continues, task completion while another profile is active, retry/replacement, and background relaunch/reattachment before implementation.

### Downloads settings — declared device scope conflicts with profile-scoped storage (8 November 2026)

DownloadsSettingsRepository explicitly documents the selected download location as a device-level preference and implements onProfileChanged() as a no-op because it loads the setting once for the process lifetime. However, both platform adapters currently read and write the download-location URI using ProfileScopedKey.of("download_location_uri"). ProfileScopedKey.of(baseKey) appends ProfileRepository.activeProfileId. Consequently, the URI loaded at first initialization depends on whichever profile is active then, while later writes can target a different profile-specific key after switching profiles. The in-memory value is not reloaded on a profile switch. This conflicts with the repository's declared ownership semantics and can make the selected location appear inconsistent across profile changes or process restarts.

No storage change was made. A migration must first define the intended owner and compatibility behaviour for existing profile-scoped values, including Android Storage Access Framework persisted URI grants and iOS security-scoped bookmarks. Promoting one profile's URI to a device-global key without an explicit migration rule could select the wrong folder or lose access to a previously authorised location. The mobile source currently establishes a device-level intent in the repository documentation, but the migration policy and precedence between legacy profile keys remain to be designed.

### iOS P2P event drain — inactivity-stop hypothesis closed by native source (8 November 2026)

A previous note raised the possibility that the full iOS playback stats poller could discard a non-error native event such as StreamStopped while draining the event queue. The exact native Engine source and both app configurations were checked to close this hypothesis.

The native LibtorrentStreamBridge::expire_inactive_streams() returns immediately when its configured inactivity timeout is zero. The mobile app sets stream_inactivity_timeout_milliseconds = 0 in both Android's engine configuration builder and the full iOS engine configuration. Therefore, the native unsolicited StreamStopped event with request ID zero and message "stream expired after inactivity" cannot be generated by this timeout path for the app's current configuration. Native explicit stop commands do emit a StreamStopped event with their non-zero command request ID; on iOS, the playback stats job is cancelled and joined before the lifecycle-locked stop command awaits its completion. There is no concurrent active-stats drain to consume that expected stop response.

The poller remains intentionally narrow: while playback is active, it consumes late torrent errors that can carry a completed command's non-zero request ID and correlates them to the active torrent; it does not add speculative handling for an inactivity-stop event that the current native configuration prevents. No P2P code change or redundant regression test was added.

Evidence references:
- Apple URLSession: https://developer.apple.com/documentation/foundation/urlsession
- Kotlin StateFlow API and atomic update: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/ and https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/update.html
- Native inactivity expiry and zero-timeout guard: https://github.com/NuvioMedia/nuvio-engine/blob/167f8541d2e07212a9cf7034b9ca4a0c3b6be4d3/src/torrent/libtorrent_stream_bridge.cpp
- Native expiry event production and explicit-stop event production: https://github.com/NuvioMedia/nuvio-engine/blob/167f8541d2e07212a9cf7034b9ca4a0c3b6be4d3/src/torrent/libtorrent_backend.cpp
- Native engine test asserting the request-ID-zero expiry event only when a non-zero inactivity timeout is configured: https://github.com/NuvioMedia/nuvio-engine/blob/167f8541d2e07212a9cf7034b9ca4a0c3b6be4d3/tests/engine_api_test.cpp

Verification limits: the complete iOS download delegate implementation, common download repository, Android download scheduler/adapter, both download storage adapters, profile-switch call path, download settings repository and profile-scoped key helper were statically inspected. The iOS P2P timeout configuration and native event generation/test path were also inspected at the exact Engine source revision used for this integration. Notes-only change; no production source or test code was modified. No Gradle test, Kotlin compilation, Android/iOS build, Xcode task, CI workflow or other build-triggering process was started. The iOS download concurrency/profile-lifecycle defects and download-location storage-scope mismatch remain open for a dedicated migration/state-machine design and regression coverage.


### Android custom DNS — honour answer lifetimes and bound resolver cache (8 November 2026)

A fresh Android networking audit found that Nuvio's custom DNS-over-HTTPS resolver has four related cache-correctness and resource-lifetime problems.

The current resolver path is confirmed:
- SharedOkHttpClient installs NuvioDns as its DNS implementation.
- DnsPlatform.android exposes that shared OkHttp client to the Ktor platform engine.
- AndroidDownloadTransfer creates its download OkHttp client with the same NuvioDns instance.
- DnsSettingsRepository exposes System, Cloudflare, Google, AdGuard and Quad9 providers; changing provider updates NuvioDns.provider and clears the current cache.
- The dependency catalog and Android module were checked: OkHttp is explicitly pinned to 4.12.0, and Ktor is 3.4.1.

Confirmed issues:
1. NuvioDns.lookup clamps every successful DNS answer TTL to at least 60 seconds with coerceIn(60, 3600). That can retain an address longer than the DNS publisher authorised, and it caches an answer whose TTL was zero even though zero means the answer is for the current transaction only. The one-hour upper cap is a valid conservative cap; the 60-second lower bound is not a safe general rule for a DoH client.
2. DnsMessage.parseAnswer updates its TTL minimum only for resource records whose type matches the current A or AAAA query. A response containing a shorter-lived CNAME record and a longer-lived terminal address record therefore caches the resolved address using only the A/AAAA TTL. The CNAME can change before that cache entry expires.
3. DohClient.query reads the DNS wire response but ignores the HTTP Age response header. A DoH response served from an intermediary cache may already have spent part of its lifetime in that cache; the remaining client cache lifetime must account for that age.
4. The positive lookup cache is an unbounded ConcurrentHashMap. It checks expiry on a lookup, but an expired entry is not removed when its hostname is not requested again. New unique hostnames therefore accumulate for the process lifetime unless the user changes DNS providers, which clears the entire map. This is an unbounded memory-retention path on a resolver used by app HTTP traffic and direct downloads.

Evidence:
- The source implementation was inspected in full: NuvioDns.kt, its DnsMessage and DohClient helpers, SharedOkHttpClient.kt, DnsPlatform.android.kt, ApiHttpClient.android.kt, DnsSettings.kt, and AndroidDownloadTransfer.kt.
- RFC 1035 section 3.2.1 defines TTL and states that zero-TTL records are only for the current transaction and should not be cached: https://www.rfc-editor.org/rfc/rfc1035.html
- RFC 8484 section 5.1 requires a DoH client to account for the HTTP Age response header when calculating the remaining DNS TTL. It also says that the HTTP freshness lifetime must not exceed the smallest TTL in the DNS Answer section, and recommends using that smallest TTL: https://www.rfc-editor.org/rfc/rfc8484.html
- RFC 2181 section 8 clarifies that TTL is a maximum lifetime, not a mandatory lifetime; an implementation may impose an upper bound, but increasing a smaller TTL is not required or implied: https://www.rfc-editor.org/rfc/rfc2181.html
- Cloudflare's DNS TTL documentation explains that cached address updates take effect according to the advertised TTL, illustrating why extending a short TTL can delay endpoint changes: https://developers.cloudflare.com/dns/manage-dns-records/reference/ttl/
- OkHttp's Dns API documentation/source states that custom DNS implementations must be safe for concurrent use and that the returned list is the ordered set of addresses attempted by OkHttp: https://github.com/square/okhttp/blob/master/okhttp/src/main/java/okhttp3/Dns.java. The current concurrent-map implementation respects concurrent access, but a future bounded cache must retain that property.

Recommended minimal implementation for a separately authorised test-first batch:
- Calculate remaining TTL as the minimum TTL across all records in the response Answer section, minus the non-negative HTTP Age value. Treat a remaining lifetime of zero or less as non-cacheable; do not promote it to 60 seconds. Continue to apply a reasonable upper cap.
- Keep the resolved addresses usable for the current lookup even when the answer must not be cached.
- Replace the unbounded map with a small access-ordered bounded cache. Expired entries should be removed when observed; insertion should evict least-recently-used entries when the capacity is reached. Keep network I/O outside the cache lock and retain provider-specific keys plus provider-change invalidation.
- Do not add request single-flight or parallel A/AAAA queries in this patch. They are separate performance policies and require measurements; the correctness/resource defects above do not depend on them.

Regression cases to add before the implementation:
- TTL zero is returned to the current caller but not cached; the next lookup performs a fresh resolution.
- A positive TTL below 60 seconds is respected rather than raised to one minute.
- An HTTP Age of 80 seconds reduces a 120-second DNS lifetime to 40 seconds; Age equal to or greater than TTL makes the result non-cacheable.
- A CNAME with a shorter TTL than its terminal A/AAAA record determines the answer-set minimum.
- More unique hostnames than the cache capacity cannot grow retained entries past the configured bound; least-recently-used entries are evicted and expired entries are removed.
- Provider changes clear retained entries, and each provider remains isolated by its storage ID.

Rejected approaches:
- Keeping the 60-second floor because it may improve cache hit rate was rejected: it can delay DNS changes and violates the zero-TTL no-cache rule. Any caching-performance trade-off must stay within the record lifetime.
- Clearing the cache on every provider change is already implemented and does not solve accumulation during normal use.
- Adding a periodic cleanup coroutine or timer is unnecessary; expiry cleanup can occur during ordinary lookup/insertion in the bounded cache.
- Adding parallel A/AAAA requests or single-flight coordination is out of scope until latency or duplicate-query measurements justify the added concurrency.

Verification limits: the complete Android DNS resolver and its consumers were statically inspected, along with the pinned OkHttp/Ktor versions and official RFC/API sources. No DNS test file currently exists in the Android host-test source set. No code or test files were changed because the required regression suite could not be run under the current no-test/build authorisation constraint; production changes are deferred until the test-first batch can be executed and reviewed. No Gradle test, compile, Android build, Xcode build or CI workflow was started.


### Network status probe coordinator — concurrency hypothesis not promoted (8 November 2026)

The NetworkStatusRepository was inspected as a separate candidate because probeInFlight and its pending flags are ordinary mutable fields, and probeInFlight is set only after a launched coroutine begins. Concurrent callers could theoretically launch overlapping probes, while an exception escaping runProbe could leave the in-flight flag set.

The source path currently confirmed in AppGate is the one-time runtime startup call to ensureStarted(). The repository tree and available code-search results did not establish another production call site for requestForegroundRefresh() or requestRefresh(). A concrete concurrent caller/reproducer was therefore not established. No code change or defect claim was promoted from this hypothesis; do not revisit it without new evidence of additional callers or an observed duplicate/stuck probe.


### Central auth repository — propagate cancellation from suspend operations (8 November 2026)

A targeted cross-provider auth audit found an inconsistency in the shared Supabase auth repository. Several suspend operations use Kotlin's standard `runCatching`, which captures `CancellationException` as a normal `Result.failure` instead of allowing the caller's cancellation to unwind.

Source-confirmed call sites in `AuthRepository.kt`:
- `validateRemoteSession` wraps `retrieveUserForCurrentSession(false)`. If its collector is cancelled during remote validation, the catch-all fallback treats cancellation as a validation/network failure and returns `true`. The caller can then publish the old `SessionStatus.Authenticated` user after `reinitialize()` has already reset state to `Loading`. In a server switch, this can briefly republish the previous backend's account identity after the old Supabase client has been reset.
- `signUpWithEmail` and `signInWithEmail` wrap suspend Auth SDK calls in standard `runCatching`; cancellation is exposed as an ordinary sign-in/sign-up failure and updates the visible auth error state rather than immediately propagating.
- `deleteAccount` similarly wraps the remote delete and sign-out operations in standard `runCatching`. A cancelled request is converted to a failure result and the caller's cancellation is delayed or swallowed. The exact desired cleanup semantics when the server may have completed deletion but the response was cancelled need dedicated tests; do not make a blind helper substitution.

The branch already contains a suitable local precedent:
- `core/concurrency/RunCatchingUnlessCancelled.kt` rethrows `CancellationException` and captures other failures.
- `RunCatchingUnlessCancelledTest.kt` covers cancellation identity, ordinary failure and success.
- Trakt and Simkl auth repositories explicitly rethrow cancellation around network operations; MDBList's disconnect path does the same. `ServerDiscoveryService.discover` explicitly rethrows cancellation from its suspending request.
- Do not blanket-replace other `runCatching` blocks in `AuthRepository`: `signOut` deliberately attempts local cleanup and resets the auth state before rethrowing a captured remote cancellation, and `prepareForServerSwitch` deliberately clears local state before rethrowing. Those are materially different cleanup contracts.

Dependency/source context:
- The branch pins Kotlin 2.4.10, kotlinx.coroutines 1.10.2 and supabase-kt 3.4.1 in `gradle/libs.versions.toml`.
- Kotlin's official cancellation guide says cancellation exceptions must be rethrown when caught so cancellation propagates: https://kotlinlang.org/docs/cancellation-and-timeouts.html
- kotlinx.coroutines issue #1814 documents the standard-library `runCatching` problem and maintainer guidance for suspendable operations: https://github.com/Kotlin/kotlinx.coroutines/issues/1814
- Supabase Kotlin's `sessionStatus` is the session lifecycle Flow: https://supabase.com/docs/reference/kotlin/auth-onauthstatechange
- Supabase-kt also merged PR #895, titled "Don't swallow CancellationException", to correct cancellation handling in its own modules: https://github.com/supabase-community/supabase-kt/pull/895. This is supporting upstream precedent; the exact contents of the pinned 3.4.1 binary were not asserted from a newer SDK docs snapshot.
- Context7 resolved official kotlinx.coroutines API documentation. Supabase Context7 content was for 3.9.0, not this repo's pinned 3.4.1, so it was not used to establish version-specific SDK internals.
- Searches did not surface a directly relevant, verifiable Reddit thread; no Reddit validation is claimed.

Recommended test-first follow-up:
- Add focused tests around an injectable/fakeable remote-session validation operation. Cancel while validation is suspended, call `reinitialize()`, then complete/cancel the old operation and assert the old collector cannot republish its authenticated user.
- Verify cancellation from email sign-in/sign-up propagates without writing an auth error or marking the request as a normal failed result.
- Verify account deletion's cancellation behaviour, including the ambiguous case where the remote action may have committed before cancellation. Preserve any mandatory local invalidation/cleanup, while not converting caller cancellation into a recoverable auth error.
- Keep tests for `signOut` and `prepareForServerSwitch` asserting their intentional "attempt local cleanup, then propagate cancellation" behaviour.
- Prefer the existing cancellation-aware helper for operations whose only correct cancellation behaviour is propagation; use a more explicit `try/finally` design where cleanup must run.

Rejected approaches:
- Replacing every `runCatching` in `AuthRepository` with the helper was rejected because sign-out and server-switch cleanup deliberately defer propagation until after local state cleanup.
- Rewriting all provider auth repositories was rejected: Trakt, Simkl and MDBList paths already explicitly preserve cancellation where needed.
- Changing Supabase SDK or Ktor versions was not justified.

Verification limits: the four central auth wrappers, auth state collector, server switch controller, device-link repository and adjacent provider implementations were statically inspected. The exact repository dependency versions and current tests were reviewed. There is no dedicated `AuthRepository` or `DeviceLinkAuthRepository` test in the current source tree. No test, compile, build, Xcode, CI or build-triggering command was started; no production or test code was changed because the red-green test cycle is not authorised.

### Account cleanup — composition cancellation can interrupt the local wipe (8 November 2026)

A deeper review of the central-auth finding exposed a separate failure mode: sign-out and account deletion are started from a composition-bound UI scope, but the local account wipe contains suspending operations and is not protected from caller cancellation.

Source-confirmed call path and behavior:
- `AccountSettingsPage.kt` creates a `rememberCoroutineScope()` and launches both `AuthRepository.signOut()` and `AuthRepository.deleteAccount()` from that scope.
- The official Compose Runtime contract says this scope is cancelled when the `rememberCoroutineScope` call leaves the composition. Navigating away from Settings while either operation is running can therefore cancel the operation.
- `LocalAccountDataCleaner.wipe()` is suspending. It switches to `Dispatchers.IO` for profile-background cleanup and Live TV cache cleanup, and its profile-removal helpers deliberately rethrow `CancellationException`. It has a `finally` for notification/member-cache cleanup, but cancellation still exits the main sequence before the remaining local stores and files are cleared.
- `AuthRepository.signOut()` captures the cleaner result with standard `runCatching`, sets auth state to unauthenticated, and only rethrows cancellation found in the remote sign-out or fallback session-clear results. A cancellation thrown by the local wipe is therefore returned as an ordinary `Result.failure`, after a potentially partial wipe.
- `AuthRepository.deleteAccount()` wraps the entire operation in standard `runCatching`. After the delete endpoint returns, it awaits Supabase sign-out before starting the local wipe. Cancellation during that sign-out skips the local wipe even though the server-side delete call already returned; cancellation during the wipe aborts it, while the `finally` still publishes unauthenticated state.
- `clearLocalSessionAfterRemoteInvalidation()` also catches local-wipe cancellation, logs it as a cleanup failure, and returns with an unauthenticated state.

This is a statically confirmed lifecycle/data-cleanup risk, not a reproduced runtime failure. It is distinct from the earlier finding that ordinary suspend operations should propagate cancellation: local account invalidation has a cleanup obligation that must be completed before caller cancellation is rethrown.

Recommended test-first follow-up:
- Give sign-out and account deletion cleanup a lifetime independent of the Settings composition once the user has confirmed the action. A repository/process-owned operation or a narrowly scoped `NonCancellable` section may be appropriate; do not move unrelated remote network work into a non-cancellable block.
- After a successful server-side account deletion response, make local session clearing and local account-data cleanup run even if the subsequent best-effort remote sign-out is cancelled or fails. Capture cancellation and propagate it only after the mandatory local cleanup has completed.
- Preserve normal failure semantics when the server delete request itself fails. Test the ambiguous case where the server may have committed deletion but the response is cancelled before the client can observe success; do not assume the outcome.
- Add deterministic tests that cancel the Settings composition during sign-out and during the post-delete sign-out step, then assert all required local cleanup phases finish, auth state is cleared, and cancellation is not converted to a normal UI error. Also test ordinary cleanup failures and remote-delete failure separately.
- Verify the same cleanup contract for Android and iOS storage adapters; do not change profile ownership, website-managed settings, or the ordering of existing cleanup operations without evidence.

Cross-checks:
- Android Developers' Compose Runtime API reference states that `rememberCoroutineScope()` returns a scope bound to the composition and that the scope is cancelled when the call leaves the composition: https://developer.android.com/reference/kotlin/androidx/compose/runtime/rememberCoroutineScope.composable
- Kotlin's cancellation guidance explains that cancellation must be rethrown when caught: https://kotlinlang.org/docs/cancellation-and-timeouts.html
- The branch's existing `LocalAccountDataCleanerTest.kt` tests profile-image deletion and storage-wipe invalidation, but does not exercise cancellation during the full account wipe or caller-composition disposal.

Rejected quick fixes:
- Merely adding `localCleanup` to `signOut()`'s list of cancellation results would propagate cancellation but would not finish a partially cancelled wipe.
- Replacing every `runCatching` in `AuthRepository` with `runCatchingUnlessCancelled` would cause cancellation to escape before mandatory cleanup has run.
- Wrapping the entire account-deletion transaction, including remote HTTP calls, in `NonCancellable` would keep unnecessary network work alive after the UI caller leaves and could delay cancellation for the full request timeout.

Source files inspected: `AccountSettingsPage.kt`, `AuthRepository.kt`, `LocalAccountDataCleaner.kt`, `LocalAccountDataCleanerTest.kt`, Android `PlatformLocalAccountDataCleaner.android.kt` and iOS `PlatformLocalAccountDataCleaner.ios.kt`.

Verification limits: the caller-scope lifecycle and cleanup control flow were verified by static source inspection and official Compose/Kotlin documentation. No regression test was run or added, no production/test code changed, and no Gradle, Xcode, CI or build-triggering process was started.

### Device-link authentication — cancel before replacing the backend client (8 November 2026)

A separate server-switch lifecycle race was confirmed by tracing the common Auth screen and server controller.

Current sequence:
1. `ServerConnectionController.switchServer` clears the existing auth session.
2. It saves the new `ServerConfiguration`, which immediately changes `ServerConfigurationRepository.active`.
3. It calls `SupabaseProvider.reset()`, closing and discarding the old cached Supabase client.
4. It reinitializes auth and refreshes network status.
5. Only then does it publish the new `activeServer` in `ServerConnectionController.state`.
6. `AuthScreen` cancels `DeviceLinkAuthRepository` from a `LaunchedEffect` keyed to that published backend URL. That effect therefore runs after the client replacement, not before it.

Meanwhile, `DeviceLinkAuthRepository` snapshots the starting `ServerConfiguration` to construct the device-link redirect URL, but resolves `SupabaseProvider.client` dynamically for the start RPC, every poll and the final exchange. Its polling job can therefore remain active across the interval where the old client is closed and the provider begins returning a client for the new backend. A poll or exchange resumed during that interval can send a device code issued by backend A to backend B; completion also imports the returned session into whichever client the global provider currently exposes.

This is a statically confirmed lifecycle window, not a runtime reproduction. It affects the shared Auth screen on Android and iOS; it does not justify a platform-specific workaround.

Recommended test-first follow-up:
- Move flow cancellation into the server-switch orchestration before the active server configuration or cached Supabase client changes. Decide explicitly whether beginning a switch cancels an in-progress device-link flow even if the subsequent session-clear/save step fails; cancelling at switch start is safest against cross-server operations but changes that failure UX slightly.
- Alternatively, introduce an explicit server-generation/client identity check at every asynchronous boundary and prohibit exchange/session import if the originating server is no longer active. Capturing only a client reference is insufficient unless completion is prevented from importing into a stale or replaced client.
- Test server A device-code creation, suspend the poll, switch to B, then resume the poll and exchange. Assert no A code is ever sent to B and no session from A is imported into B. Also test a switch that fails before the backend change and the intended device-link UI state.
- Keep the screen-disposal cancellation as a lifecycle safeguard; it should not be the only server-switch boundary.

Evidence is the exact source path and revisions: `ServerConnectionController.kt` (`7febe0370d7aac99320e8629fb5dac8d887c66a0`), `AuthScreen.kt` (`1ca8849eaf50dd73a96c86fdd86ee3007461d341`), `DeviceLinkAuthRepository.kt` (`7868acbe4331938460890bfe6ed3034ce977480e`), `ServerConfiguration.kt` (`59690c9874fb43b2f072691026b0f707137aa070`) and `SupabaseProvider.kt` (`06544ce8f93fe40f982fcd1e5dc934e75b82d6ce`).

Verification limits: all relevant common source files and the UI cancellation effect were inspected. This timing window was not exercised at runtime because tests are not authorised. No production/test code changed and no build or CI was started.

### In-app log buffer — retryable StateFlow update has a side effect (candidate not promoted) (8 November 2026)

`InAppLogger.appendEntry` calls `syncLines(updated)` from inside the transformation passed to `_entries.update { current -> ... }`. The pinned kotlinx.coroutines API documents that the update function may be evaluated more than once when concurrent updates contend. Publishing the mirrored `_lines` StateFlow inside that retryable transformation is therefore a side effect that can run for failed compare-and-set attempts and can expose an intermediate `lines` snapshot that does not correspond to the committed `entries` value.

The same hot path also copies the retained list at the 3,000-entry cap (`drop(1) + entry`) and rebuilds a second 3,000-element string list on each append. That is O(capacity) work and allocation per log event while the buffer is full. This is a plausible efficiency issue, but no representative event-rate/allocation measurement was made.

Context:
- The debugging settings page currently collects `InAppLogger.entries`, and renders filters plus the last 500 rows. It does not establish that the compatibility `lines` StateFlow is actively consumed elsewhere.
- iOS MPV diagnostics are already coalesced in the player bridge before forwarding log events, so do not assume unbounded per-frame log spam from that path.
- Kotlin's official `MutableStateFlow.update` API states the transform can be evaluated multiple times: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/update.html

No change is recommended until all `lines` consumers and cross-platform log writers are established and a meaningful workload is measured. A future test should stress concurrent append/clear and assert the retained entries and compatibility lines remain aligned, with no duplicate externally visible side effects. If addressed, retain the 3,000-entry bound, order and log text; use a synchronization/state ownership design rather than moving `syncLines` after the atomic update without considering races.

Verification limits: common logger, settings log viewer, Android/iOS timestamp actuals and iOS MPV bridge were inspected. No logger tests or event-rate benchmarks were run. This remains a candidate only; no code or test files were modified.


### Add-on synchronization — propagate cancellation and isolate refresh results by profile (8 November 2026)

A new audit of the add-on repository found three cancellation-swallowing network paths and one concrete profile-switch state race.

Source-confirmed behavior:
- `AddonRepository.pullFromServer(profileId)` wraps its complete suspending sync body in standard `runCatching`. The caller in `SyncManager` is `runOrderedProfileSync`, whose `runStep` correctly rethrows `CancellationException`. Because the add-on repository turns cancellation into a normal result and only logs it, the sync coordinator can treat an interrupted Addons step as a normal return and continue into later steps until another suspension observes cancellation.
- `AddonRepository.addAddon` catches `Throwable` around the manifest HTTP fetch and parser. This includes cancellation and can convert a cancelled deep-link/manual add-on install into a normal `AddAddonResult.Error`.
- `AddonRepository.refreshAddon` wraps the suspend fetch/parse in standard `runCatching`, then always applies its result to the current `_uiState` by comparing only `manifestUrl`.
- `AddonRepository.onProfileChanged` cancels and clears its independent refresh-job map, changes `currentProfileId`, resets loading flags and replaces the in-memory list. Those refresh jobs are launched in a repository-owned `SupervisorJob + Dispatchers.Default` scope, not as children of the profile-sync request.
- If a cancelled old-profile refresh catches its cancellation and reaches the subsequent state update after the new profile has loaded an add-on with the same manifest URL, it can clear that new profile entry's `isRefreshing` flag and publish the old job's cancellation as a manifest error. The active-job map's identity check protects removal of a newer job entry, but it does not guard the shared UI-state update by profile or generation.

The relevant profile sequence is real: `ProfileRepository.selectProfile` updates the active profile and calls `AddonRepository.onProfileChanged`; the app then requests a new profile pull through `SyncManager`. The sync request gate cancels the previous full-profile request, while the add-on refresh jobs are separately cancelled by the repository. Shared add-ons across profiles make the same manifest URL a realistic collision key.

A further reason not to blanket-change this file: `pushToServer` already catches `CancellationException` first and rethrows it, and its push-job registry is keyed by profile ID. Keep that behavior.

Recommended test-first follow-up:
- Test cancellation of `pullFromServer` while its remote request is suspended and assert the cancellation reaches `runOrderedProfileSync` rather than being recorded as a successful Addons step.
- Start profile A's manifest refresh for URL U, switch to profile B, load the same URL U and start B's refresh, then release A's cancelled request. Assert A cannot change B's `isRefreshing`, manifest or error state and cannot remove B's job registry entry.
- Test `addAddon` cancellation to ensure it propagates without becoming a user-visible normal installation error.
- Preserve `finally` cleanup of `activeRefreshJobs`; rethrow cancellation before any state mutation. Consider capturing a profile-generation token alongside the profile ID and require it to still match before applying any result, because cancellation alone is not a substitute for ownership checks around non-suspending parsing/state updates.
- Review `pullFromServer` state writes and use a captured profile ID/generation for every request, local storage read/write and state commit. Do not rely on the mutable repository-wide `currentProfileId` after a suspension.

Cross-checks:
- Kotlin's official cancellation guide requires caught cancellation to be rethrown so structured cancellation is preserved: https://kotlinlang.org/docs/cancellation-and-timeouts.html
- kotlinx.coroutines issue #1814 documents the standard-library `runCatching` cancellation hazard and maintainer guidance: https://github.com/Kotlin/kotlinx.coroutines/issues/1814
- The repository already has `runCatchingUnlessCancelled` and its tests, and `SyncManager.runOrderedProfileSync` explicitly handles cancellation as a separate control path.
- This is not a reason to replace every parser/URL-normalization `runCatching` in add-on code: synchronous decoding/URL parsing is not a cancellable network operation.

Rejected approaches:
- Adding a second refresh timer or retry loop was not justified; the issue is ownership/cancellation of existing jobs.
- Keying only the active refresh map by URL is insufficient to prevent stale results from changing a later profile's UI state. The map's object-identity removal check should remain, but state publication also needs a profile/generation guard.
- Broadly rewriting the sync coordinator was not justified; its cancellation handling is already explicit and correctly distinguishes cancellation from ordinary step errors.

Source revisions inspected: `AddonRepository.kt` (`8b3ccf3e0e9259e225183a0ab5e6356049d0d1a7`), `ProfileRepository.kt` (`be26d1861594d0671535f7e15c7172ef70849b18`), `SyncManager.kt` (`a8a49eb6f1b6d9eeacdff2e45b4b6f113328981f`), and the existing cancellation-aware helper `RunCatchingUnlessCancelled.kt` (`7bd938118bad9240b0fb4cf551d1939eac439e2d`).

Verification limits: the full add-on state lifecycle, profile-switch call ordering, sync request cancellation path and current sync tests were statically inspected. No dedicated AddonRepository cancellation/profile-race test exists in the current common-test tree. No tests or builds were started, and no production/test code was changed because the test-first cycle remains unauthorised.


### Full plugin repository — cancelled refresh can publish across profile boundaries (8 November 2026)

The full-feature plugin repository was audited after the add-on findings. It has a separate profile-persistence generation/revision system, but that protection applies to persistence snapshots; it does not protect in-memory refresh-result publication.

Source-confirmed behavior in `fullCommonMain/features/plugins/PluginRepository.kt`:
- `pullFromServer(profileId)` resolves/loads a profile, then wraps the whole suspend sync body in standard `runCatching`. It logs all failures (including cancellation) and returns normally, so the ordered sync runner cannot distinguish an interrupted Plugins step from an ordinary completed step.
- `addRepository(rawUrl)` catches `Throwable` around `fetchRepositoryData`, converting cancellation into an ordinary repository-install failure.
- `refreshRepositoryInternal` starts jobs in a repository-owned `SupervisorJob + Dispatchers.Default` scope. It wraps `fetchRepositoryData` in standard `runCatching`; after cancellation it still enters the state-update block and writes its result by matching only `manifestUrl`.
- `onProfileChanged` cancels the refresh jobs and resets `currentProfileId` and `_uiState`, but does not invalidate a profile/generation token for the active refresh result. If profile A and B both have a repository at URL U, an A refresh cancelled during the request can still apply its failure/result to B's U entry. The update can overwrite B's name, version, scraper list, enabled preferences (captured from A's previous scraper map), refreshing flag and error state. It then calls `persist()`, whose new snapshot uses the current profile B and can persist that mixed A/B state to B.
- The existing refresh-job map uses an object-identity guard before removing a job entry; retain this protection. It does not prevent the separate shared-state update.
- `fetchRepositoryData` captures `storageProfileId` before its network work, which is good for scraper-code file placement. However, every scraper code fetch is itself wrapped in standard `runCatching { httpGetText(codeUrl) ... }.getOrNull()`; cancellation can be mistaken for an optional scraper failure and the manifest loop continues rather than immediately aborting. The parent refresh still needs an explicit generation guard when it attempts to publish its final result.
- `pushToServer` is another repository-owned coroutine with standard `runCatching`; it has no captured profile snapshot or job registry. Its exact remote ordering/overwrite semantics were not established, so no separate lost-update or wrong-account defect is asserted.

Important distinction: `PluginPersistenceSnapshot(profileId, generation, revision, state)`, the monotonically increasing persistence revision and per-profile persisted revision map already protect stale persistence snapshots from overwriting newer snapshots of the same profile. Do not remove or duplicate that work. The gap is between profile ownership of a live refresh and the subsequent UI-state commit.

Platform scope:
- This implementation is in `fullCommonMain`, shared by the full-feature mobile variants. The inspected Android Play Store and iOS App Store source sets deliberately use disabled plugin stubs; do not apply this finding to those variants.
- The current Android Full host test suite has tests for empty/non-empty remote sync, failed sync preserving cache, refresh without upload, and explicit removal upload. It has no cancellation or cross-profile refresh-race test.

Recommended test-first follow-up:
- Start repository A's URL U refresh and suspend it at manifest or scraper-code fetch; switch to profile B with the same URL U and start B's refresh; resume A's cancellation. Assert A cannot change any of B's repository/scraper state, B's enabled flags, or B's refresh-job registration, and cannot persist A's stale preferences to B.
- Cancel `pullFromServer` during the remote select and assert cancellation propagates to `runOrderedProfileSync`, not logged as a completed Plugins step.
- Cancel `addRepository` during its fetch and assert the caller sees cancellation rather than `AddPluginRepositoryResult.Error`.
- Ensure a cancelled scraper-code request aborts the refresh rather than silently dropping that scraper and returning a partial repository.
- Capture a profile ID plus generation when the operation starts; check that identity immediately before any `_uiState` update, persistence snapshot or push. Cancellation is necessary but cannot replace the ownership check.
- Preserve the existing per-profile persistence revision logic, job identity removal check, profile sharing rules and disabled platform stubs.

Cross-checks and existing architecture:
- Kotlin's official cancellation guidance and kotlinx.coroutines issue #1814 explain why catching `CancellationException` as an ordinary result breaks structured cancellation: https://kotlinlang.org/docs/cancellation-and-timeouts.html and https://github.com/Kotlin/kotlinx.coroutines/issues/1814
- This repository's `SyncManager.runOrderedProfileSync` explicitly rethrows cancellation, and its Android Full host tests already use MockWebServer to exercise real repository sync/refresh behavior. Extend that existing test style rather than inventing a new test harness.
- The plugin code's generation/revision persistence mechanism is real and should be retained; it is not equivalent to checking that the same profile/generation still owns an asynchronous refresh result.

Other plugin-runtime observations not promoted:
- `PluginRuntime.executePlugin` uses `withTimeout(60_000)` and rethrows the resulting failure with `getOrThrow()`; no timeout defect is asserted there.
- `getPluginSettingsLayout` has an outer `catch(Exception)` around a `withTimeout` scope. This is a cancellation-sensitive pattern, but it was not promoted without a dedicated timeout/caller-cancellation test against the exact runtime implementation.
- `FetchBridge.performNativeFetch` bridges the synchronous QuickJS host callback with `runBlocking` around the suspend HTTP transport. This can block the plugin execution thread until the request finishes; Android uses an OkHttp call with cancellation wired to the runBlocking job, while iOS uses the Ktor Darwin client. The plugin runtime and HTTP read/connect timeouts are bounded, so no change was justified without measuring real fetch contention or confirming the QuickJS API's supported asynchronous bridge contract.
- GitHub code searches for the plugin concurrency constant and server RPC identifier returned no useful indexed results; no conclusions depend on those searches.

Source revisions inspected: full plugin repository (`082de91cd4c391a9a893a57c9b223cd79f0d9d66`), plugin runtime (`a5a1cc763ae26665c6c672ead362026632e6a61c`), FetchBridge (`0b97b0f10febce1ffe87f96bd9fb0b43470b48fb`), Android Full host test `PluginSyncTest.kt` (`47fff071e3c59c0a9936cfd16be025d5b120ea03`), Android Play Store and iOS App Store disabled stubs (`a42594a1b0b314e13507121af17de3c0152c96b4`), and Addon HTTP platform implementations Android (`9d1f94dd0425149bb03ab361a8a8411daff6c966`) / iOS (`c556d5b8fd3c15d8dca9a88898ef4c0224206883`).

Verification limits: this was static source and test inspection only. No plugin cancellation/profile race was reproduced, no tests were run, and no test/build/CI files or production source were changed. No Gradle, Xcode or build-triggering process was started.


### Watch-party session ownership — reject stale snapshots and queued room messages (8 November 2026)

A focused audit of the watch-party state machine found two related asynchronous ownership gaps. Both could publish data from a previous party after the user had joined another party.

#### Stale RPC snapshots

`PartyRepository` runs its work on `Dispatchers.Default.limitedParallelism(1)`, but several remote actions are launched in the repository-wide scope and suspend during PostgREST RPCs:
- `removeMember` captures the active party, awaits `watch_party_remove_member`, then applies the returned snapshot.
- `setHostOnly` optimistically updates local state, awaits `watch_party_set_host_only`, and previously rolled back or applied the returned snapshot without confirming that the same party remained active.
- `publishMedia` similarly applies the completed `watch_party_update` result by mutating whichever party is active at completion time.
- `refreshMembers` awaits a heartbeat and also emits a follow-on `MemberJoined` event using the live repository state.

The start/create/join path is mutex-protected, but these independent commands do not hold that mutex across their network operations. A request started for party A can therefore resume after a switch to party B. Previously `applySnapshot` matched no request owner: in particular, a party-A error snapshot such as `not_member` or `party_ended` could trigger rejoin/finish handling against party B. Even when snapshot application is rejected, `refreshMembers` also needs to suppress its follow-on event rather than reading and announcing a member from the new room.

#### Queued chat messages

`PartyChannel.open` collects broadcast events inside the party session scope. `PartyRepository.onMessage` forwards a received chat payload to `PartyChat.receive`, which launches a new coroutine in PartyChat's separate process-lifetime scope. Closing the old PartyChannel cancels its collector jobs and removes the channel, but it cannot retract a chat callback that has already been queued in PartyChat's independent scope. Before this change, `PartyChat.receive` only checked that some room was active; a queued event from party A could therefore be decoded and appended after party B had become the active room.

#### Implemented change

The following minimal shared-code changes were committed:
- Added `isPartySessionCurrent` and `isPartySnapshotCurrent` ownership predicates. Error snapshots may omit party details, so they are accepted only when the original request's party is still the active party; a response that identifies a different party is rejected.
- Every asynchronous snapshot publication path now passes its captured party ID into `applySnapshot`, which checks both the captured request owner and the current session before processing success or error responses.
- The `setHostOnly` optimistic rollback is now conditional on the original party still being active.
- `refreshMembers` checks session ownership before publishing its follow-on member-joined event.
- `PartyRepository` now passes the event's originating party ID to `PartyChat.receive`. The queued PartyChat job drops the payload unless that exact room is still active.

This does not add locks around network operations, change the party protocol, alter playback sequence ordering, or change user-visible layout/animation behaviour. It is shared Kotlin code used by Android and iOS; no platform-specific implementation was needed.

#### Regression coverage and evidence

Added `PartySessionOwnershipTest` covering: a current session with a matching response; a server error snapshot with no party body; a response from another party; an old request completing after a switch; and a request completing after the party has ended.

Research and implementation cross-checks:
- Kotlin's official [Coroutine context and dispatchers documentation](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html) explains dispatcher confinement and that coroutines can suspend and later resume; single-thread confinement does not make a multi-suspension network operation atomic with respect to other coroutines.
- Kotlin's official [Shared mutable state and concurrency documentation](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html) describes thread confinement as a way to protect state, while the repository's suspending RPC boundaries still permit interleaving between confined coroutines.
- The repository's own `PartyChannel.kt` confirms that broadcast collectors are children of the party session scope and are cancelled during channel close. It also shows the handoff to `PartyRepository.onMessage`; PartyChat then deliberately uses a separate process-lifetime scope, which is why an explicit room-owner check is needed at the second asynchronous boundary.
- Supabase Kotlin 3.4.1 release notes and its official [removeChannel guidance](https://supabase.com/docs/reference/kotlin/removechannel) were checked to distinguish channel teardown from cleanup of already-forwarded application work. Channel removal is not a substitute for validating the owner of an already-enqueued application callback.
- Community searches surfaced Supabase Realtime concurrency/reconnect discussions, but no discussion was treated as proof of this app-level state race. The finding is established by the app's source-level data flow.

Rejected alternatives:
- A broad mutex around all watch-party RPCs would serialize unrelated operations and hold coordination while waiting on network I/O. The request already has a stable party ID, so an ownership check at publication is smaller.
- Checking only `snapshot.party.id` is insufficient because error snapshots can have `party == null`; the captured request party must also match the active local party.
- Relying only on `PartyChannel.close()` is insufficient because chat callbacks are re-dispatched to PartyChat's independent scope.
- Comparing only the live room to a payload-derived field is not possible: broadcast chat payloads do not carry the party ID. The channel callback's captured party ID is the correct owner token.
- A server repository first-use concern was also checked: `ServerRepository.call` captures its generation before `connection()` ensures loading, which can increment the generation. Current callers reach `refreshLibraries` from `MediaServerSettingsPage` after `MediaServersSettingsPage`/the detail page has already called `ensureLoaded()`; no failing current caller was found, so this was not changed.

Source files reviewed: `PartyRepository.kt`, `PartyChat.kt`, `PartyChannel.kt`, `PartyApi.kt`, `PartyModels.kt`, and the existing Party sync/chat/stream tests. New regression test: `PartySessionOwnershipTest.kt`.

Verification limits: complete remote diffs and all snapshot/chat receive call sites were re-fetched after the change. The branch diff was limited to the party model helper, party repository, party chat repository and the new common test, plus this cumulative note. Tests were authored but not executed. No Gradle task, Kotlin compilation, Android/iOS build, Xcode task, CI workflow or build-triggering process was started. Runtime reproduction and compiler verification remain outstanding.


### Authentication session ownership and device-link backend binding (8 November 2026)

The previous static auth findings were implemented in a focused shared-code batch after adding common-test coverage for the new ownership gates.

#### Ordinary suspend auth cancellation

- `AuthRepository.validateRemoteSession` now uses the existing `runCatchingUnlessCancelled` helper instead of standard `runCatching`. A cancelled session validation can no longer be converted into the existing "keep cached authentication" fallback.
- `signUpWithEmail` and `signInWithEmail` also use the cancellation-aware helper. Caller cancellation is propagated rather than being reported as an ordinary authentication error.
- Both email operations capture the current `SupabaseClient` before the request. After the SDK operation returns, the coroutine checks cancellation and confirms the captured auth-session generation is still current before setting `trustNextSessionWithoutValidation`. A late operation from a backend being replaced cannot mark the next backend's session as trusted.
- `AuthSessionGeneration` serializes generation changes with the state-publication action. The session-status collector captures both its generation and Supabase client at initialization, and all status publication goes through the generation guard. A late validation from an invalidated collector cannot republish an authenticated user after a server switch.
- `prepareForServerSwitch` invalidates the old generation before clearing the session and also resets the one-shot trusted-session flag. `reinitialize` invalidates the previous generation before installing a new collector.
- Invalid-session cleanup receives the captured client's clear-session operation, rather than resolving the global provider again after a suspend point. It checks generation ownership between its remote clear and local cleanup stages, so an old collector does not clear the newly selected backend's session or start a stale local wipe after invalidation.

#### Device-link backend ownership

- `DeviceLinkAuthRepository` now captures the active `SupabaseClient` once and passes that same instance through device-code creation, every poll, token exchange, user retrieval and `importSession`. It no longer resolves the global provider dynamically at each stage.
- The attempt checks both coroutine cancellation and its generation after every suspendable backend boundary and immediately before session import. A late response from an old attempt cannot proceed into another request or publish a stale UI state after cancellation.
- `DeviceLinkFlowGate` allows only one active attempt, invalidates pending work on cancel, and blocks new attempts for the full duration of a server switch. Device-link state publication and job registration are serialized with the lifecycle lock.
- `ServerConnectionController` begins the device-link block synchronously before clearing auth or saving the new configuration, and releases it in `finally` on success, ordinary failure or cancellation. The controller now has a synchronous switch-entry lock as well, so two rapid switch actions cannot both pass the previous asynchronous `isSwitching` check.
- On a session-clear/save failure, the auth collector is reinitialized against the unchanged server. Cancellation/restart failures also restore a collector for whichever server configuration is current before the switch gate is released.

The server-switch behavior is intentional: starting a server switch cancels any active device-link attempt even when the later session-clear or configuration-save step fails. The flow returns to Idle and the user may start it again after the switch operation ends. This avoids carrying a device code across a backend lifecycle boundary.

#### Regression coverage

Added:
- `AuthSessionGenerationTest`: a newer collector generation invalidates the previous one, and explicit invalidation prevents stale publication.
- `DeviceLinkFlowGateTest`: only one attempt can be active; cancellation invalidates pending work; server switching blocks new attempts; and finishing an old attempt cannot release a newer attempt.

These tests are in `commonTest`, matching the shared implementation scope. They were authored before the production changes. They were not executed because the user has not authorised Gradle test/compile tasks. No runtime reproduction is claimed.

#### Cross-checks and compatibility

- The existing project helper `RunCatchingUnlessCancelled.kt` and `RunCatchingUnlessCancelledTest.kt` were reused rather than introducing another result wrapper.
- Kotlin's official [cancellation and timeouts guide](https://kotlinlang.org/docs/cancellation-and-timeouts.html) says cancellation is cooperative and caught `CancellationException` should be rethrown.
- The official [ensureActive API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/ensure-active.html) supports the explicit post-SDK cancellation checks where a suspending implementation may have returned after the attempt was invalidated.
- kotlinx.coroutines [issue #1814](https://github.com/Kotlin/kotlinx.coroutines/issues/1814) documents why the standard library's `runCatching` can swallow cancellation in coroutine code.
- The repository pins kotlinx.coroutines 1.10.2, Kotlin 2.4.10 and Supabase Kotlin 3.4.1. The new client type `io.github.jan.supabase.SupabaseClient` is already used in `SupabaseProvider.kt`; no SDK API, dependency version, Ktor engine, platform source set or build configuration was changed. Supabase Kotlin's [3.4.1 release record](https://github.com/supabase-community/supabase-kt/releases/tag/3.4.1) was checked for version context.
- Exa and community searches did not produce a more authoritative device-code client-switch implementation than the codebase's own client lifecycle plus Kotlin cancellation guidance. Reddit did not provide directly relevant, source-verifiable evidence and is not claimed as validation.

#### Rejected approaches and retained boundaries

- Do not blanket-convert `signOut`, `deleteAccount`, `prepareForServerSwitch` or every auth `runCatching` to the cancellation-aware helper. Those paths intentionally attempt local state cleanup before reporting remote cancellation/failure, and account deletion needs its own explicit post-commit cleanup semantics.
- Do not resolve `SupabaseProvider.client` again during polling or exchange. It can return a different backend client after reset; the original client is the owner of the device code and returned session.
- Do not rely only on the Auth screen's server-URL-keyed `LaunchedEffect`; it observes the server switch after that controller has already changed the backend.
- No new retry loop, delay, dependency or UI state was introduced.

Source files changed: `AuthRepository.kt`, `AuthSessionGeneration.kt`, `DeviceLinkAuthRepository.kt`, `DeviceLinkFlowGate.kt`, and `ServerConnectionController.kt`. Added tests: `AuthSessionGenerationTest.kt` and `DeviceLinkFlowGateTest.kt`.

Still open from the previous audit:
- Account sign-out/deletion can still be interrupted by composition cancellation during mandatory local cleanup. In particular, `deleteAccount` still needs an explicit post-success cleanup sequence that preserves caller cancellation without skipping local invalidation. It was deliberately not folded into this batch.
- Add-on and full-plugin cancellation/profile-generation races remain documented and unchanged.
- In-app log-buffer StateFlow side-effect/allocation observations remain candidates pending consumer confirmation and measurement.

Verification limits: the full changed auth/device-link/controller files, helper classes, tests and dependency pins were re-fetched and statically reviewed. No Gradle test task, compilation, Android build, iOS/Xcode build, CI workflow or build-triggering process was started. The code has not been compiler- or runtime-verified. The complete remote diff and final branch head are checked separately after this note is committed.


### Follow-up verification limits — authentication and add-on lifecycle (8 November 2026)

A final review of the auth/device-link batch confirmed the intended remote diff (eight files including this documentation file) and checked all current Supabase client references in `DeviceLinkAuthRepository`: the repository provider is resolved once per attempt, and the captured client is passed through start, poll, exchange, user retrieval and session import. The server switch calls `beginServerSwitch` before session clearing/configuration changes and releases the gate from `finally`.

Important test limitation:
- `AuthSessionGenerationTest` verifies generation invalidation.
- `DeviceLinkFlowGateTest` verifies one-attempt ownership, cancellation invalidation, switch blocking and stale completion.
- These tests do **not** yet drive a fake Supabase client A through a suspended poll while switching to client B, nor assert that the A device code is never sent to B and that A's returned session is never imported into B.
- The current repository directly resolves `SupabaseProvider.client` when starting a flow and has no injectable client/network test seam. A deterministic integration test likely needs a narrowly scoped internal workflow collaborator or injected client provider; that design should be completed before claiming the full A-to-B race is runtime-verified. Do not add an abstraction solely for tests without keeping production ownership explicit.
- None of the tests were executed and no compiler/build task was run, per the user's instruction.

The add-on repository finding was revalidated against the current branch after the auth change:
- `pullFromServer(profileId)` still assigns `currentProfileId` before its suspending query and uses standard `runCatching`; after a profile change, later reads, migration writes and UI publication can observe the mutable repository-wide profile ID.
- `addAddon` still catches `Throwable` around its suspending manifest fetch/parse, so cancellation can still be returned as `AddAddonResult.Error`.
- `refreshAddon` still captures its network result with standard `runCatching` and applies the result by manifest URL without a captured profile/generation ownership check. Its active-job identity guard only protects map cleanup, not the UI-state commit.
- The only common add-on test file is `AddonModelsTest.kt`; it tests parsing/models, not repository jobs, storage, Supabase sync or profile-switch ordering. No repository test seam exists today.
- This remains a worthwhile follow-up, but a cancellation-helper substitution alone would leave the stale profile publication and storage ownership race. No partial add-on code change was made. The implementation should capture a profile/generation token and apply it consistently before each post-suspension state/storage/push boundary, with a deterministic repository test that suspends an A request, switches to B, then resumes A.

These are explicit verification and scope limits, not evidence that the auth or add-on races have been reproduced at runtime. Account sign-out/deletion cleanup cancellation also remains open as documented in the preceding section.


### Server-switch session-import drain — await credential persistence before clearing the old client (8 November 2026)

Further source inspection of the earlier auth/device-link changes found a missing synchronization boundary: checking the flow generation after a Supabase call returns is not enough when Supabase itself imports and persists a session inside that suspend call.

#### Pinned SDK evidence and root cause
- The app pins `supabase = 3.4.1`, `kotlinx-coroutines = 1.10.2`, Kotlin `2.4.10`, and `atomicfu = 0.32.1` in `gradle/libs.versions.toml`.
- The version-pinned Supabase Kotlin 3.4.1 `Auth.kt` defines `signInWith`, `signUpWith` and `importSession` as suspend APIs. In the matching 3.4.1 `AuthImpl.kt`, provider sign-in/sign-up invoke a callback that calls `importSession` before the operation returns.
- The version-pinned 3.4.1 `SessionManager.kt` defines `saveSession` as a suspend function. The SDK's session-import path can therefore suspend while writing the new session to storage before the outer `signInWith`/`signUpWith` coroutine completes.
- Device-link completion also calls the same suspending `client.auth.importSession`. A generation/attempt check immediately before the call does not serialize that in-progress storage write with `clearSession()` on the server-switch path.
- The prior implementation cancelled device-link and credential jobs but did not await them before clearing/replacing the old client. A cancelled operation can still be unwinding a suspendable session save; a clear performed concurrently can happen before that save finishes.

#### Implemented synchronization boundary
- Added `AuthSessionOperationGate`, which atomically registers email sign-in/sign-up jobs, blocks new registrations during a server switch, and returns all currently registered jobs to the switch coordinator. It uses unique operation IDs so concurrent sign-in/sign-up jobs do not invalidate one another.
- Wrapped `AuthRepository.signInWithEmail` and `signUpWithEmail` in the operation gate. Their existing cancellation-aware result handling and generation checks remain in place. The tracked job stays registered until the entire SDK call (including its internal session import) has unwound.
- Guarded the synchronous anonymous sign-in storage/state update with `runIfOpen`, preventing a local anonymous session write from racing into the middle of a backend switch.
- `DeviceLinkAuthRepository.beginServerSwitch()` still closes the device-link gate synchronously before the switch coroutine starts, cancels the active attempt, and now retains its Job. `awaitServerSwitchStopped()` joins that cancelled job before the old client's session is cleared.
- Added `cancelAndJoinNonCancellable`, which first cancels operations, then waits for their completion in a narrowly scoped `NonCancellable` region. After the drain it calls `ensureActive()` on the original caller context: cleanup is awaited, but a cancelled server-switch request still aborts before proceeding to session clearing/configuration replacement.
- `ServerConnectionController` ordering is now: synchronously acquire the switch lock and close the device-link gate; await the device-link job; close/cancel/drain registered email credential jobs; clear the old client's local session; save the next configuration; reset the client; reinitialize Auth; release both gates in `finally`.

#### Regression coverage added
- `AuthSessionOperationGateTest` covers pre-switch job enumeration, independent concurrent operation ownership, blocking new credential operations while switching, reopening after switch completion, protection against an old operation finishing a newer registration, and rejecting synchronous anonymous-session writes while switching.
- `CancelAndJoinNonCancellableTest` verifies that a cancelled operation's `finally` cleanup completes before the drain returns, and that cancellation of the waiter is propagated only after the target cleanup finishes.
- Tests were added before their corresponding production gate/helper implementation, but were not run. The test-first failure (RED) and passing (GREEN) states have therefore not been observed and are not claimed.

#### Cross-checks
- Version-pinned Supabase Kotlin 3.4.1 source: [Auth.kt](https://raw.githubusercontent.com/supabase-community/supabase-kt/3.4.1/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/Auth.kt), [AuthImpl.kt](https://raw.githubusercontent.com/supabase-community/supabase-kt/3.4.1/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/AuthImpl.kt), [SessionManager.kt](https://raw.githubusercontent.com/supabase-community/supabase-kt/3.4.1/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/SessionManager.kt), and [SupabaseClient.kt](https://raw.githubusercontent.com/supabase-community/supabase-kt/3.4.1/Supabase/src/commonMain/kotlin/io/github/jan/supabase/SupabaseClient.kt). These exact tag sources were preferred over Context7's Supabase 3.9.0 snippets, which do not match the app's pinned SDK.
- Kotlin's official [Job.cancelAndJoin API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/cancel-and-join.html) states that the operation cancels a Job and suspends until it is complete; the call itself is cancellable. The helper shields only the wait with `NonCancellable`, then explicitly re-checks the caller's cancellation state.
- Kotlin's official [cancellation and timeout guide](https://kotlinlang.org/docs/cancellation-and-timeouts.html) describes cooperative cancellation and the need to preserve caller cancellation. Context7's kotlinx.coroutines API material independently agrees with the `cancelAndJoin` contract; Context7's Supabase catalogue only offered 2.6.0 and 3.9.0, so it was not used for the pinned SDK semantics.
- Searches of GitHub issues/discussions and developer discussions surfaced general Supabase session persistence and coroutine cancellation reports, but no stronger application-specific reference than the pinned SDK source. Community findings were treated as supporting context, not proof. No directly relevant Reddit thread with verifiable implementation evidence was found.

#### Rejected approaches and remaining limitations
- A post-call generation check alone is insufficient because Supabase's sign-in/sign-up call performs `importSession` internally before it returns; the session storage write may suspend before the caller can check its generation.
- Cancelling a device-link job without joining it is insufficient because the same job may be suspended in `importSession`/`SessionManager.saveSession` while the switch tries to clear the old client's session.
- Wrapping the whole sign-in, device-link or remote account-deletion HTTP request in `NonCancellable` was rejected. The network request must remain cancellable; only the bounded drain needed before a destructive local session replacement is shielded.
- The full backend-A/backend-B device-link integration test is still absent. The new tests cover the operation gate and drain helper, not a fake Supabase client whose in-flight `saveSession` resumes during a switch. The current object repositories do not expose a test-injection seam. Add that integration test only with a narrow seam that preserves production client ownership.
- Mandatory local account cleanup after sign-out/account deletion remains a separate unresolved problem. This batch does not change `deleteAccount`, `signOut` or `LocalAccountDataCleaner`; deletion's remote request ambiguity and local cleanup contract need separate tests. Add-on and full plugin profile-generation races also remain open.

Files changed in this batch: `AuthRepository.kt`, new `AuthSessionOperationGate.kt`, `DeviceLinkAuthRepository.kt`, new `CancelAndJoinNonCancellable.kt`, `ServerConnectionController.kt`, new `AuthSessionOperationGateTest.kt`, and new `CancelAndJoinNonCancellableTest.kt`.

Verification limits: remote source and all changed call sites were re-fetched; the complete diff against the previous reviewed head contains only these seven implementation/test files plus this note. Tests were authored but not executed. No Gradle test or compile task, Android build, iOS/Xcode build, CI workflow or build-triggering process was started. The current code is not compiler- or runtime-verified.


### Account cleanup and profile-owned add-on/plugin work (8 November 2026)

This follow-up began from the previous authentication/server-switch investigation and continued the open account-cleanup and add-on/plugin profile-isolation items. The working branch remained `main-build-testing`; no branch switch, merge, reset or rebase was performed.

#### Account cleanup — root cause and changes

- `LocalAccountDataCleaner.wipe()` previously ran its cleanup body sequentially, so an exception from an early operation (for example, clearing scheduled episode-release notifications) prevented later profile credential, cache, library and setting cleanup from being attempted.
- Added `runAccountCleanupSteps` to execute every independent local cleanup step, collect failures, and aggregate them after the finalizers. `EpisodeReleaseNotificationsRepository.onAccountCleanupFinished()` and `MemberAccessRepository.onAccountCleanupFinished()` remain in a `finally` path and are independently attempted.
- Added `runNonCancellableCleanup`: the local cleanup sequence runs under `NonCancellable`, then the original caller context is checked with `ensureActive()`. A pending cancellation is propagated after mandatory local cleanup; if cleanup also failed, that failure is attached as suppressed diagnostic context. The protected region contains local operations only. Remote delete/sign-out requests remain cancellable.
- Added a reference-counted account-cleanup hold to the add-on and plugin profile-operation gates. `LocalAccountDataCleaner.wipe()` closes the add-on gate before cleanup starts and closes the full-plugin gate only when `AppFeaturePolicy.pluginsEnabled` is true, matching the existing plugin-clear policy; both are released with the notification/member-access finalizers in its `finally` block. Overlapping cleanup paths cannot reopen either gate until every corresponding cleanup invocation has finished. New pulls, refreshes, mutations and cache writes are refused for the entire wipe window, preventing a still-visible settings screen from reloading old account data or enqueueing a new server push between in-memory clearing and platform-storage deletion. Play Store and App Store plugin actuals expose no-op methods so the common expect/actual contract remains complete without enabling plugin functionality in those builds.
- Profile background images, PIN cache payloads, biometric material, Live TV cache, platform storage and the existing repository/cache reset list retain their existing ownership and deletion targets. Failures from background-image, PIN-cache, biometric and Live TV cleanup are surfaced in the final incomplete-cleanup result rather than silently treated as success.
- `AuthRepository.signOut()` now attempts local session clearing (if remote sign-out failed) and all local cleanup steps after the remote request has finished or failed. It propagates cancellation after the local cleanup boundary and reports ordinary cleanup failures.
- `AuthRepository.deleteAccount()` now treats a successful `delete-account` function response as the irreversible-success boundary. After that confirmation, it attempts remote sign-out but does not let a sign-out failure skip local `auth.clearSession()` and local account-data cleanup. The delete request itself remains cancellable; if it is cancelled or fails before a success response is received, whether the backend committed the deletion is not knowable from this client result and remains a backend-contract/reconciliation question.
- Invalid-session recovery now treats anonymous local auth-key removal as best-effort, continues to clear the Supabase session and local account data, and propagates captured cancellation after cleanup rather than exiting at the first local-key failure.
- Pinned Supabase Kotlin 3.4.1 `AuthImpl.clearSession()` only deletes session/code-verifier storage, updates local `sessionStatus`, and stops auto-refresh; it does not issue a logout HTTP request. This makes it suitable for the local fallback after the remote request has already been attempted.

#### Add-ons — profile-owned asynchronous work

- Added `AddonProfileOperationGate` and common-test coverage. Its token carries both the effective storage profile and a generation; leaving a profile and later returning to the same profile index does not revive an earlier operation.
- Guarded initial local-state publication, server pulls, add-manifest completion, refresh publication, synchronous remove/move/enable mutations, and profile-scoped persistence with the operation token.
- Preserved secondary-profile `usesPrimaryAddons` behavior by resolving the effective profile for storage and ownership while confirming the requested profile remains active. No parallel per-secondary copy or new storage key was introduced.
- Add-on installation captures its Supabase client only after URL/duplicate/profile validation and before the manifest request. Deferred push work captures an immutable repository snapshot, fixed profile ID and the originating Supabase client before the debounce, and is tracked/cancelled by profile.
- Profile transitions and account cleanup invalidate the token before cancelling old refresh registrations. Registration and cancellation share the gate ordering so a refresh cannot be inserted between invalidation and cancellation.
- Account cleanup now holds the add-on gate closed from the start to the finalizer of the entire wipe, so operations cannot restart against old local data after `clearLocalState()` but before platform storage deletion finishes. Initial local add-on URL/enabled-state reads and state publication are serialized inside the gate, preventing concurrent initializers from publishing different snapshots or exposing `initialized=true` before the state is ready.
- Corrected lazy refresh de-duplication: a `CoroutineStart.LAZY` job is not yet `isActive`, so an `isActive`-only check could permit a second refresh before the first was started. The repositories now consider any registered non-completed job already scheduled.

#### Full plugins — profile, cache and deferred-push ownership

- Added `PluginProfileOperationGate` in the full-feature source set with Android full-host tests. It tracks the effective profile and generation and serializes profile invalidation with state publication and synchronous scraper-code cache writes.
- The gate is held closed for the complete account-cleanup window and released in the cleaner's `finally` path. The common expect/actual API is implemented as no-op by the Play Store and App Store disabled-plugin variants; full-feature Android/iOS use the actual gate.
- Guarded plugin server pulls, repository adds/removals, scraper toggles, plugin-setting changes, refresh publication and repository-state loading. A suspended manifest/code fetch checks its operation after each network boundary; cancellation is rethrown, not converted into a normal fetch error.
- Each scraper-code cache write is performed inside the profile gate with the operation's captured profile ID. `persist(operation)` captures a profile-specific immutable snapshot only while the operation is current. An already-captured snapshot may still finish writing to its own profile-scoped key after a normal profile switch, preserving the prior repository behavior; it cannot publish into the newly active profile. The complete file-cache + metadata snapshot write is serialized under `persistenceLock`. Account cleanup increments `persistenceGeneration` while holding that same lock: a write already in progress finishes before the wipe, while a write arriving after the cleanup generation changes is rejected. Per-profile `persistenceRevision` ordering and the stored-state schema remain unchanged.
- Local plugin state restoration is serialized inside the profile gate, so concurrent `initialize`/`pullFromServer` calls cannot race to republish different disk snapshots.
- Deferred plugin pushes now capture repository rows, profile ID and Supabase client at the user action/refresh boundary, track one pending push per profile, cancel the previous queued job, and rethrow cancellation. They no longer read the mutable current profile or whichever Supabase client happens to be active after the debounce.
- Full-feature Android/iOS implementations continue to use their existing profile-indexed scraper-code paths. The Android Play Store and iOS App Store disabled-plugin stubs were not changed. No plugin runtime, manifest schema, backend RPC payload or platform storage format was changed.

#### Source and API evidence checked

- App dependency pins rechecked in `gradle/libs.versions.toml`: Kotlin 2.4.10, kotlinx-coroutines 1.10.2, atomicfu 0.32.1, Ktor 3.4.1 and Supabase Kotlin 3.4.1.
- Supabase Kotlin 3.4.1 tagged sources, not later 3.9.0 snippets: [AuthImpl.kt](https://raw.githubusercontent.com/supabase-community/supabase-kt/3.4.1/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/AuthImpl.kt), [Auth.kt](https://raw.githubusercontent.com/supabase-community/supabase-kt/3.4.1/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/Auth.kt), and [SessionManager.kt](https://raw.githubusercontent.com/supabase-community/supabase-kt/3.4.1/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/SessionManager.kt). These establish the suspend auth/session-storage APIs and local `clearSession()` behavior.
- Kotlin coroutine cancellation and context documentation: [cancellation and timeouts](https://kotlinlang.org/docs/cancellation-and-timeouts.html), [withContext](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html), and [Job.cancelAndJoin](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/cancel-and-join.html). The local-only `NonCancellable` cleanup pattern is bounded and explicitly re-checks the original caller context afterwards.
- The current repo's [Android add-on HTTP transport](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/blob/main-build-testing/composeApp/src/androidMain/kotlin/com/nuvio/app/features/addons/AddonPlatform.android.kt) uses `suspendCancellableCoroutine` and calls `OkHttp Call.cancel()` on cancellation. Its [iOS transport](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/blob/main-build-testing/composeApp/src/iosMain/kotlin/com/nuvio/app/features/addons/AddonPlatform.ios.kt) uses suspendable Ktor/Darwin requests. The profile guards therefore address the actual post-response/cache/publication race without introducing another HTTP abstraction.
- Plugin persistence and ownership were checked against the current [full-feature PluginRepository](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/blob/main-build-testing/composeApp/src/fullCommonMain/kotlin/com/nuvio/app/features/plugins/PluginRepository.kt), [Android full PluginStorage](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/blob/main-build-testing/composeApp/src/androidFull/kotlin/com/nuvio/app/features/plugins/PluginPlatform.android.kt), and [iOS full PluginStorage](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/blob/main-build-testing/composeApp/src/iosFull/kotlin/com/nuvio/app/features/plugins/PluginPlatform.ios.kt).
- Exa searches and Context7 documentation were used to corroborate the Kotlin cancellation contract and discover related SDK/community pitfalls. Community threads were treated as leads only; code changes were grounded in the pinned dependency source and actual repository implementations. No directly relevant social-media or Reddit implementation was found that was stronger than those sources.

#### Tests added and verification limits

- `LocalAccountDataCleanerTest` now has focused tests for continuing after multiple cleanup-step failures and completing required cleanup before caller cancellation is propagated.
- `AddonProfileOperationGateTest` covers profile invalidation, stale-publication rejection, repeated reads for the same active profile, switch-away/switch-back invalidation, refusing new work until account cleanup is finished, and overlapping cleanup holds.
- `PluginProfileOperationGateTest` covers profile refresh invalidation, rejecting a stale scraper-cache write, returning to a prior profile without reviving old work, repeated reads for the same profile, blocking work for the full account-cleanup window, and overlapping cleanup holds.
- Tests were written before the corresponding new gate/helper code, but were not run. RED/GREEN behavior has not been observed and is not claimed. The repository-level async behavior still needs integration tests with controllable HTTP responses and storage to prove races end-to-end.
- No Gradle test/compile task, Android build, iOS/Xcode build, CI workflow, or build-triggering process was started. These changes are source-reviewed only and are not compiler/runtime verified.

#### Rejected approaches and remaining gaps

- Rejected wrapping remote delete, remote sign-out, plugin HTTP fetches or add-on HTTP fetches in `NonCancellable`; these requests must remain cancellable.
- Rejected changing plugin/add-on storage schemas, Supabase RPC payloads, backend ownership, plugin runtime execution or the App Store/Play Store plugin-disable stubs; the demonstrated races can be addressed at existing ownership/persistence boundaries.
- Rejected using only a profile integer as an operation identity; switching away and returning to the same index can make stale work appear current. The generation token is required.
- A cancellation or timeout while the remote delete-account request is in flight can leave its server outcome ambiguous if the server committed but the response was lost. A reliable reconciliation/status contract is a backend concern and was not invented client-side.
- Cancelling a tracked add-on/plugin push stops local work and its cancellable transport, but cannot roll back an RPC the backend has already accepted. The current repository changes do not add a server-side deletion tombstone, RPC idempotency key or transaction contract; verify the deployed RLS/RPC behavior before claiming that a sign-out/account deletion prevents every already-accepted write from committing.
- Add-on/plugin gate tests do not yet simulate the full repository with an in-flight HTTP response, storage write, account wipe and profile transition. Add end-to-end fake transport/storage tests with a narrow injection seam before treating this work as fully verified.
- The previous backend-A/backend-B in-flight Supabase session-import integration test is still outstanding.


### Sentry network breadcrumbs and plugin fetch errors — remove URL secrets from diagnostics (8 November 2026)

A security/privacy pass over the Android add-on HTTP client found that its custom Sentry interceptor removed query strings and fragments but not URL user-info or path data. This matters for user-supplied add-on/plugin endpoints that can embed Basic Auth credentials in a URL or use opaque/private path tokens. The interceptor also copied the raw URL path and raw exception message into separately named breadcrumb fields, which bypassed the URL query/fragment stripping. The full plugin fetch bridge separately wrote the unredacted request URL and exception object to Kermit and copied the exception message into the in-app log buffer on failure.

#### Pinned-source evidence and root cause

- The app pins OkHttp 4.12.0 in `composeApp/build.gradle.kts`, Sentry 8.47.0 in `gradle/libs.versions.toml`, and Kermit 2.0.5 in the version catalog.
- The Android `AddonHttpClientProvider` installs `SentryNetworkBreadcrumbInterceptor` on the client used by add-on/plugin HTTP requests. The interceptor's prior `scrubbedUrl()` removed only query and fragment; it retained URL username/password and the endpoint path.
- Exact OkHttp 4.12.0 tagged source documents `HttpUrl.redact()` as stripping username, password, query and fragment and replacing the path with `/...`, while retaining the URL's scheme and host. This avoids a hand-written URL parser and is available in the app's pinned version.
- Exact Sentry Java 8.47.0 `Breadcrumb.http(url, method)` source parses the supplied URL and stores it as the breadcrumb's `url` data field. The interceptor additionally added `path` and `error_message` custom fields. Sentry's official Java data-scrubbing guidance warns that application-defined breadcrumb data may require custom scrubbing; setting `sendDefaultPii=false` or clearing the event's HTTP request/user fields does not by itself remove arbitrary fields manually attached to breadcrumbs.
- The plugin fetch bridge's failure branch called Kermit with the original URL and Throwable (including its message/stack) and placed `throwableSummary(t)` in the local debug log. Exception messages can themselves include a full request URL, so redacting the URL argument alone is not enough.

#### Implemented changes

- Sentry's URL now uses the pinned OkHttp `HttpUrl.redact()` implementation. The breadcrumb retains the request scheme/host and generic redacted path, without user-info, endpoint path, query or fragment.
- Removed the separately attached raw `path` field and raw `error_message` field. Method/status, elapsed duration, severity, host and exception type remain available for request diagnostics.
- The plugin fetch error log no longer includes the raw URL or Throwable. Kermit and the in-app debug log now record only the request method and exception type. The exception message and original URL remain in the JSON fetch-error result returned to the plugin, preserving the existing plugin-facing response contract.
- Added two Android host regression tests for credentials/path/query/fragment redaction and retention of scheme/host.

#### Cross-checks and scope

- OkHttp 4.12.0 tagged `HttpUrl.kt`: https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/HttpUrl.kt
- Sentry Java 8.47.0 tagged `Breadcrumb.java`, `Scope.java` and `SentryOptions.java`: https://raw.githubusercontent.com/getsentry/sentry-java/8.47.0/sentry/src/main/java/io/sentry/Breadcrumb.java, https://raw.githubusercontent.com/getsentry/sentry-java/8.47.0/sentry/src/main/java/io/sentry/Scope.java, https://raw.githubusercontent.com/getsentry/sentry-java/8.47.0/sentry/src/main/java/io/sentry/SentryOptions.java
- Sentry's official Java sensitive-data guidance: https://docs.sentry.io/platforms/java/guides/spring/data-management/sensitive-data/
- Context7's Kermit API reference confirms the message-lambda error overload used to record an exception type without attaching the Throwable. The project version catalog pins Kermit 2.0.5; no dependency was changed.
- No request URL, authentication header, response body, request body, plugin API shape, redirect policy, HTTP configuration, download behavior or platform storage was changed. No global `beforeBreadcrumb` policy was added, avoiding changes to unrelated breadcrumbs.

#### Verification and remaining limits

- Re-fetched the target branch head immediately before the change and confirmed it was still `42e9d3dfc49d33244da86fcd942bd0bc1c450e0b`; no remote divergence was observed at that point.
- The complete changed source files and the new test file were inspected after writing, together with the Android client construction path, plugin fetch bridge, Sentry initializer, pinned dependency declarations and test source-set dependencies.
- Regression tests were authored but not run. No Gradle test/compile task, Android build, iOS/Xcode build, CI workflow or build-triggering command was started, as explicitly required. The change remains source-reviewed, not compiler- or runtime-verified.
- The Sentry breadcrumb intentionally continues to include the request host and the plugin fetch's plugin-facing error response intentionally retains the original URL/message. This is deliberate compatibility/diagnostic scope, not a claim that all telemetry or plugin-visible data is anonymised. If hostnames themselves are considered sensitive for this app's Sentry policy, that requires a separate decision to remove host-level correlation.
- Final review was performed by the author in this session; no reviewer/subagent tool is available in the connected tool set.
