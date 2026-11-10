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


### iOS add-on raw HTTP — stream response bodies within the configured cap and align request policies (8 November 2026)

A source-level comparison of Android and iOS `httpRequestRaw()` found a memory-safety/performance issue plus two concrete platform parity gaps.

#### Root causes confirmed in the branch

- The Android adapter reads an OkHttp response stream incrementally and stops at `maxResponseBodyBytes`, retaining only the configured prefix plus a one-byte truncation probe.
- The iOS adapter called `response.body<ByteArray>()`, which fully materialised the response before it copied only the configured prefix. The default limit is 1 MiB, but the actual allocation therefore scaled with the untrusted server's entire response, not with the intended cap. A large or unbounded add-on/plugin response could consume excessive memory or terminate the app before the cap was applied.
- Android's raw request-body predicate explicitly included POST, PUT, PATCH and DELETE. iOS only set a body for POST, PUT and PATCH, silently dropping a supplied DELETE body.
- The common `httpRequestRaw` contract exposes `followRedirects`; Android chooses a redirect-disabled OkHttp client when it is false, while iOS ignored the argument and always used its default Ktor client.

#### Implementation

- iOS now executes raw requests with Ktor 3.4.1 `prepareRequest { ... }.execute { ... }` and reads `bodyAsChannel()` incrementally. The reader grows its working byte array only as bytes arrive, stops at the configured maximum, and probes at most one additional byte to preserve the existing `...[truncated]` marker behavior for known- and unknown-length responses. It checks the channel close cause so a failed short response is not quietly reported as complete. The returned `bodyBytes`, decoded `body`, URL, status, status text and headers retain the existing response contract.
- Ktor's streaming `HttpStatement.execute(block)` also performs response cleanup in a `finally` block. The application does not retain an open channel or add another coroutine/job for this request.
- The redirect-disabled iOS client is created lazily only when a caller passes `followRedirects = false`; the existing default client continues to serve normal requests. The redirect policy is now honoured without replacing the platform URLSession delegate or changing redirect behaviour for the other add-on HTTP methods.
- Request-body eligibility now has one shared common helper used by both Android and iOS. DELETE bodies are sent on iOS in the same cases already supported on Android; GET, HEAD and other unsupported methods remain bodyless.
- Added common tests for the shared request-body method contract and iOS tests for capped reads, buffer growth past the initial 16 KiB allocation, exact-limit responses, zero-byte limits, and empty responses.

#### Evidence and research cross-checks

- The branch pins Ktor 3.4.1; no dependency or build configuration change was needed.
- Ktor 3.4.1 `HttpStatement.kt` shows that `execute(block)` obtains a streaming response and calls `response.cleanup()` in `finally`. Its streaming fetch path marks the request to skip eager body saving; its cleanup cancels the raw body channel and joins the response job.
- Ktor 3.4.1 `HttpResponse.kt` exposes `bodyAsChannel()`, and `ByteReadChannelOperations.kt` documents/implements `readAvailable(ByteArray, offset, length)`, EOF and channel close-cause behavior.
- Ktor's official response documentation states that ordinary `HttpResponse.body()` loads the full response into memory and recommends `prepareGet/prepareRequest(...).execute { ... }` with `ByteReadChannel` for streaming: https://ktor.io/docs/client-responses.html#streaming
- Exact sources: https://github.com/ktorio/ktor/blob/3.4.1/ktor-client/ktor-client-core/common/src/io/ktor/client/statement/HttpStatement.kt, https://github.com/ktorio/ktor/blob/3.4.1/ktor-client/ktor-client-core/common/src/io/ktor/client/statement/HttpResponse.kt, https://github.com/ktorio/ktor/blob/3.4.1/ktor-io/common/src/io/ktor/utils/io/ByteReadChannelOperations.kt
- Ktor 3.4.1 `HttpClientConfig.kt` defines `followRedirects`; `HttpClient.kt` installs `HttpRedirect` only when that property is true. The 3.4.1 Darwin `KtorNSURLSessionDelegate.kt` returns `null` in `willPerformHTTPRedirection`, leaving redirection to Ktor's own plugin. This confirms separate clients with different config values are the correct supported boundary for this API. Sources: https://github.com/ktorio/ktor/blob/3.4.1/ktor-client/ktor-client-core/common/src/io/ktor/client/HttpClientConfig.kt, https://github.com/ktorio/ktor/blob/3.4.1/ktor-client/ktor-client-core/common/src/io/ktor/client/HttpClient.kt, https://github.com/ktorio/ktor/blob/3.4.1/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/KtorNSURLSessionDelegate.kt
- Ktor's official redirect documentation: https://ktor.io/docs/client-redirect.html
- Practitioner corroboration: Ktor issue #4900 (2025) reproduces whole-response memory growth from non-streaming `.get()` and reports resolution with `.prepareGet(...).execute { ... }`: https://github.com/ktorio/ktor/issues/4900. Older iOS reports #1639 and #1000/#1167 were treated as historical context only; they do not establish a Ktor 3.4.1 defect. The pinned 3.4.1 Darwin source was checked directly.
- No direct Reddit/social post was sufficiently specific or stronger than the tagged Ktor source and maintainers' issue discussion; none is used as evidence.

#### Scope, compatibility and verification limits

- Android's body-size reader and redirect selection were otherwise left intact. The shared method helper preserves the exact existing Android POST/PUT/PATCH/DELETE set. The iOS default redirect-enabled client, timeout values, request headers, body serialization, response URL/status/headers, text decoding and truncation suffix remain unchanged.
- Only the lazily-created no-redirect client is additional; no redirect behavior is changed for normal requests.
- The returned response retains no more than `maxResponseBodyBytes` response bytes plus its one-byte truncation probe. Temporary array growth can briefly overlap old and new buffers, so peak allocation is O(cap), not exactly cap; it does not scale with the full response size.
- Re-fetched the latest branch head before editing and confirmed it was `0bb8fd601343c4984bf0f44ebee9cca3bf60f9a5`. Changed platform files, Ktor 3.4.1 source, source-set dependencies and the new regression tests were inspected.
- Regression tests were authored but not executed. No Gradle tests, compile, Android build, iOS/Xcode build, CI workflow or other build-triggering command was started. The change remains statically reviewed, not compiler- or runtime-verified.


### YouTube trailer extraction — preserve caller cancellation across fallback probes (8 November 2026)

A focused cancellation audit of the full in-app YouTube trailer extraction path found that ordinary Kotlin `runCatching` was used around several suspending network operations. Kotlin's standard `runCatching` catches every `Throwable`, including `CancellationException`. In this path that could make an abandoned trailer lookup continue through alternative YouTube clients/probes, and on iOS a cancelled response-body read could be converted to an empty body while the request was still reported as successful.

#### Affected paths and root cause

- `InAppYouTubeExtractor.extract()` wrapped the entire extraction and its 30-second `withTimeout` in `runCatching`. This converted both caller cancellation and the extractor's own timeout into an indistinguishable ordinary failure.
- The per-YouTube-client fallback loop wrapped `fetchPlayerResponse()` and parsing in `runCatching`. Cancelling the player lookup could be treated as a failed client and the loop would proceed to later clients.
- HLS manifest retrieval in `resolveHls()` also ran inside `runCatching`, which could consume cancellation during its suspend request.
- iOS `TrailerExtractionPlatform.performRequest()` wrapped `response.bodyAsText()` in `runCatching` and substituted an empty string. A cancelled response read could therefore yield a response object containing an empty body.
- The Android and iOS `isUrlReachable()` implementations wrapped their suspend probe operations in `runCatching`, converting cancellation into an ordinary unreachable result.

#### Implementation

- Reused the existing `com.nuvio.app.core.concurrency.runCatchingUnlessCancelled` helper at every affected suspend boundary. It preserves the existing `Result` fallback contract for ordinary failures while rethrowing `CancellationException` unchanged.
- Replaced the outer extractor timeout with `withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS)`. The extractor's own 30-second deadline still returns `null` and is logged as a timeout, while cancellation from the caller remains cancellation rather than being converted to a recoverable no-result response.
- The per-client fallback loop and HLS candidate fallback still continue for ordinary HTTP/parsing errors; they now stop when the caller cancels rather than continuing to other candidates.
- iOS response-body read failures still produce the existing empty-body fallback for ordinary non-cancellation failures, but cancellation now propagates.
- Android and iOS reachability probes still return false for ordinary probe failures, but cancellation now propagates out of the probe tier. The Android probe still uses its existing synchronous OkHttp `execute()` on `Dispatchers.IO`, so an already-running call can take up to its existing 2-second per-call timeout to return before coroutine cancellation is observed; this patch does not make that blocking call immediately interruptible.
- No candidate priority, probe count, request URL/headers/body, per-request timeout, response data shape, media selection, playback UI or navigation was changed.

#### Evidence and cross-checks

- The helper already existed at `composeApp/src/commonMain/kotlin/com/nuvio/app/core/concurrency/RunCatchingUnlessCancelled.kt` and has common tests proving cancellation identity, ordinary failure capture and successful-value preservation. This change reuses it rather than introducing another helper.
- Kotlin's official [coroutine cancellation guidance](https://kotlinlang.org/docs/cancellation-and-timeouts.html) requires cancellation exceptions to be rethrown after cleanup instead of treated as ordinary failures.
- Kotlin's [withTimeoutOrNull API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout-or-null.html) returns null for its own timeout and propagates nested/parent timeout cancellation that does not belong to that invocation.
- Exact Ktor 3.4.1 `HttpTimeout.kt` declares `HttpRequestTimeoutException` as an `IOException`; the Ktor connection/socket timeout types are also I/O exceptions. Thus individual Ktor request timeouts remain ordinary recoverable client failures inside `runCatchingUnlessCancelled`, preserving the existing fallback to the next client. The extractor's separate outer `withTimeoutOrNull` is the overall deadline. Source: https://github.com/ktorio/ktor/blob/3.4.1/ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/HttpTimeout.kt
- Ktor's tagged 3.4.1 Darwin adapter uses that configured request timeout and maps NSURLSession timeout errors to the Ktor socket timeout I/O type: https://github.com/ktorio/ktor/blob/3.4.1/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/TimeoutUtils.kt
- The Android probe remains a synchronous OkHttp call with its existing 2-second per-call timeout on `Dispatchers.IO`; it was not converted to a new transport/callback abstraction.

#### Verification limits

- Inspected the complete common extractor, both Android/iOS trailer transport implementations, the existing shared cancellation helper and its tests, the Ktor 3.4.1 timeout source and current pinned dependency declarations.
- Added tests confirming the helper preserves its own `withTimeoutOrNull` timeout as a successful null result and rethrows caller cancellation through that timeout wrapper. Existing helper tests also cover cancellation identity, ordinary failure and success.
- Tests were authored but not executed. No Gradle test/compile task, Android build, iOS/Xcode build, CI workflow or build-triggering command was started. These changes are statically reviewed but not compiler- or runtime-verified.


### Trailer extraction response buffering — full-body reads shared, cap deferred (8 November 2026)

The same final iOS networking sweep found that the full YouTube trailer extractor still buffers watch-page HTML, player API JSON and HLS manifest responses into strings on both platforms: Android uses OkHttp `ResponseBody.string()`; iOS uses Ktor `bodyAsText()`. This is separate from the arbitrary add-on/plugin `httpRequestRaw()` path, where an existing 1 MiB contract made eager iOS allocation clearly incorrect and streaming was implemented.

No cap or streaming refactor was applied to trailer extraction. The common `TrailerRequestResponse.body` contract and current parsers consume complete HTML/JSON/playlist text; the code currently has no payload-size telemetry or fixture corpus establishing safe content-specific bounds for YouTube watch pages and player responses. An arbitrary small cap risks truncating the embedded API key, `streamingData`, or HLS variant list and silently regressing fallback selection. The Android and iOS implementations currently share the same full-body behavior, so this is not a new iOS-only parity regression.

A future change should first measure representative production/test fixtures, then set separate conservative bounds by response role (watch HTML, player JSON, HLS manifest), preserve explicit truncation/failure semantics, and test valid large responses plus over-limit handling on Android and iOS. Until those thresholds are evidence-based, keep the existing behavior. The cancellation hardening above does not claim to bound these trailer response allocations.

Sources inspected: `composeApp/src/androidFull/kotlin/com/nuvio/app/features/trailer/TrailerExtractionPlatform.android.kt`, `composeApp/src/iosFull/kotlin/com/nuvio/app/features/trailer/TrailerExtractionPlatform.ios.kt`, and `composeApp/src/fullCommonMain/kotlin/com/nuvio/app/features/trailer/InAppYouTubeExtractor.kt`. No code or test changes were made for this deferred candidate.


### YouTube trailer reachability probes — cancel native requests and discard unused response bodies (8 November 2026)

A focused Android/iOS comparison found that the media-host reachability probe only needs the HTTP response status, but its platform implementations had different resource costs and cancellation behaviour.

#### Root cause confirmed at the platform boundary

- Android's isUrlReachable() built an OkHttp GET with Range: bytes=0-0, then called synchronous Call.execute() from a coroutine on Dispatchers.IO. The dispatcher avoided blocking the UI thread, but each parallel host candidate occupied an IO worker while waiting for response headers. Coroutine cancellation was not connected to the active OkHttp call, so an abandoned probe could continue until its existing two-second call timeout.
- iOS's isUrlReachable() reused the general performRequest() helper. That helper called HttpClient.request() and then bodyAsText(), materialising the response body as a String even though the probe only checks response.status. If a server ignores the one-byte Range request or returns an unexpectedly large body, this does unnecessary network transfer and allocation.
- Both platforms already run a maximum three-second probe tier, keep a two-second per-probe timeout, issue the same range GET and preserve candidate ordering. The change does not alter those policies.

#### Implementation

- Android now uses the same cancellable OkHttp callback pattern already used by the app's add-on transport: enqueue(), invokeOnCancellation { call.cancel() }, status-only inspection, and Response.use to close the unread body immediately. Ordinary network failures still mean unreachable; caller cancellation remains cancellation. The synchronous probe call no longer holds an IO worker while the network request is pending.
- iOS now builds the same GET through Ktor 3.4.1 prepareRequest { ... }.execute { response -> ... } and reads only response.status. It no longer calls the full-body performRequest() path for reachability. Ktor's scoped streaming execution releases the response in a finally cleanup path, including when the block returns without reading the body or the caller cancels.
- Kept the exact two-second request/connect/socket timeout values, three-second overall tier timeout, Range and User-Agent headers, success status range, parallel candidate set/order, fallback order, and returned URL behaviour. No player, extraction, playback, request or UI contract was changed.

#### Pinned implementation and documentation evidence

- The app pins OkHttp 4.12.0. Its tagged Call.kt defines asynchronous enqueue(Callback) and cancel(); tagged Response.kt documents Response as Closeable and confirms close() closes the underlying response body:
  - https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Call.kt
  - https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Response.kt
- The exact Ktor 3.4.1 HttpStatement.kt was inspected. execute(block) obtains a streaming response via fetchStreamingResponse(); that path marks the request to skip saving the full body, and execute(block) invokes response cleanup in finally. Cleanup cancels the unread raw response channel and joins its request job:
  - https://raw.githubusercontent.com/ktorio/ktor/3.4.1/ktor-client/ktor-client-core/common/src/io/ktor/client/statement/HttpStatement.kt
- Context7's Ktor response/timeout documentation corroborates scoped streaming and per-request timeout configuration. The project already uses prepareRequest().execute { ... } with ByteReadChannel for bounded iOS add-on responses; no new Ktor pattern or dependency is introduced.
- The exact MockWebServer 5.3.2 deprecated API source was inspected to confirm the test's setBodyDelay and setHeadersDelay methods, matching the repository's existing okhttp3.mockwebserver imports:
  - https://raw.githubusercontent.com/square/okhttp/parent-5.3.2/mockwebserver-deprecated/src/main/kotlin/okhttp3/mockwebserver/MockResponse.kt

#### Regression coverage and verification limits

- Added Android full-host tests proving a probe returns from response status without waiting for a delayed response body, cancellation cancels a pending OkHttp call, and non-2xx status is classified as unreachable.
- The Android host-test target already has MockWebServer 5.3.2, and androidFullHostTest includes its source directory only for the full Android distribution. No dependency or Gradle configuration change was made.
- The regression tests were authored first, but neither the RED nor GREEN run was performed: running Gradle tests would trigger a build, which remains unauthorised. No Gradle test/compile task, Android build, iOS/Xcode build, CI workflow or build-triggering command was started.
- Static review compared the full Android/iOS platform files, their common caller, the existing Android add-on callback implementation, exact pinned OkHttp/Ktor sources, the MockWebServer test API and the source-set/dependency configuration. This is source-reviewed only; compiler, test and runtime results remain unverified.
- The existing awaitAll().firstOrNull candidate-selection behaviour was deliberately retained. Selecting the first network response to arrive would change the existing deterministic candidate-order preference, which is outside this resource/cancellation fix.


### Cross-platform source repair — restore pre-merge mobile behaviour and resolve compile blockers (9 November 2026)

A Linux full-distribution Android host-test run and a full iOS Debug IPA build were started against the post-trailer-networking source snapshot `203496f6c34740de551fbfe8f4dab2d9ec2b323d`. Both reached Kotlin compilation and exposed related source regressions that originated in the 8 November original Liquid Glass merge (`b096d4c3330617ecc3bff60573f834cf6b1de5a9`). The failures were therefore investigated against both merge parents, rather than attributed to the latest network-probe change.

#### Failed baseline and root causes established

- Linux run `42538701145` failed at `:composeApp:compileAndroidMain`; the Android full-host test task and APK task did not run.
- iOS run `42538701105` passed Xcode/toolchain and dependency preparation, then failed at `:composeApp:compileKotlinIosArm64` during Kotlin compilation.
- The common error groups mapped to the same merge regressions on both platforms:
  - The merge removed the existing `core/ui/background` common implementation and its Android/iOS actuals while leaving `AppEnvironment`, the app shell, dialogs and player callers dependent on those APIs. This removed the app background/Glass UI host, its settings storage API, the background-active composition local, the backdrop API and the software-bitmap support used by the consumers.
  - The merged Live TV screen no longer contained the pre-merge on-demand, sports and guide sections and removed the `onVodClick` parameter, while the app shell still supplied it. This was a functional regression as well as a compile failure.
  - Several files retained code from one Liquid Glass implementation while using types/signatures from the other merge parent: a malformed Details action-button Surface block, a stale detail-header glass modifier call, missing trailer selector state, and a missing player glass import.
  - The search screen contained a composable `DiscoverFilterRow` call inside the retry callback, causing parser/composable-context errors; an additional `NuvioTokens` reference lacked its import.
  - The common Ktor bounded-body reader passed a `Long` to the Ktor 3.4.1 `readBuffer(Int)` API.
  - Add-on refresh fallback code called suspending `getString()` from a non-suspending `Result.fold` callback.
  - Episode notification persistence retained an unnecessary nullable type after its early-return null guard.
  - Party companion dispatch called `PartyChat.receive(payload)` without the expected party ID, bypassing the method's room-identity argument.

#### Implemented repair

- Restored the known-good enhanced-parent `AppBackground.kt`, `AppBackgroundSettings.kt`, and Android/iOS platform actuals. The source is taken from the first merge parent (`a159060c48eba3c9aa2c7cd5dc0302582de19135`), not recreated from assumptions.
- Restored `AppEnvironment(drawAppBackground = true)` and the original single AppBackgroundHost integration. The existing platform entrypoints already host the sheet backdrop, so the common app environment does not wrap a second sheet host.
- Restored Android `AppBackgroundStorage.initialize(applicationContext)` in `MainActivity`, at the same lifecycle point used by the pre-merge enhanced implementation. Without this, the restored storage actual would not have an initialised Android context.
- Restored the pre-merge Live TV screen implementation containing channel/guide, VOD and sports behaviour, including the `onVodClick` API required by the app shell. Carried forward a profile/auth-keyed `LaunchedEffect` for channel loading so the old one-shot initialization does not regress profile switching.
- Repaired the Details action menu using the second parent's valid `Surface`/Liquid Glass shape block; changed the detail header to the current `liquidGlassForSurface` helper, with the explicit backdrop and preview arguments now passed to the correct extension.
- Restored the trailer selector's surface/enabled state variables required by its existing selector UI, without replacing its user-facing layout.
- Removed only the incorrectly nested search retry callback filter row; retained the correctly placed discovery filter row in the search header and added the missing `NuvioTokens` import.
- Passed `link.partyId` to the PartyChat receiver so the existing party identity check is used.
- Added the missing `ambientLiquidGlass` import to PlayerSidePanel.
- Changed the bounded Ktor read to pass an `Int` byte count and reject negative/`Int.MAX_VALUE` caps before adding the one-byte truncation probe, avoiding overflow.
- Moved the add-on localized refresh fallback lookup into the suspending coroutine before the non-suspending state update.
- Let the episode notification payload type be inferred after the null early return, so the storage call receives the non-null string it requires.

#### Verification workflow and source-review record

- The repair is committed on `main-build-testing` as `07f384473e89d38ae0d564e6cf5087802bbc4ee2`, a normal fast-forward from `3e06627287a56bfafdbe7d61ee16a840bce49962`. No branch switch, merge, force push, PR or update to `enhanced` was made.
- The full Git comparison from the previous head contains 17 source paths, covering the restored four-file background layer, Android background initialization, AppEnvironment, Live TV and the specific compile blockers above. No dependencies, version catalog entries, Gradle build configuration, signing/provisioning files, backend configuration, settings ownership, or UI resources were changed.
- A separate validation branch `ci/nuvio-main-build-testing-validation-20261109` carries the Linux/iOS workflows and is based on the exact source commit. The Linux step runs `./gradlew :composeApp:testAndroidHostTest -Pnuvio.android.distribution=full -Pnuvio.ios.distribution=appstore --stacktrace`, followed by `:androidApp:assembleFullDebug`. The iOS workflow uses Xcode 27.1 and the repository's `scripts/prepare-ios-dependencies.sh` and `scripts/build-ios-ipa.sh` for an unsigned full Debug IPA.
- Current-head validation commit: `aaebb02d04353c2473f516f33e92e11afc0747d0` (parent is the repaired app commit).
  - Linux workflow run: `42541044666`
  - iOS workflow run: `42541044652`
- At the time of this note, both workflows were still in progress: Linux was executing the Android full-host test task and iOS was executing the unsigned full Debug IPA build. No current-head test, APK or IPA result is claimed. The earlier baseline failures remain recorded separately; their logs do not describe the repaired source.
- Review was performed by comparing the full source files with both merge parents, rechecking all diagnosed call sites, and verifying the Liquid Glass helper signature, Ktor 3.4.1 API and Android/iOS background initialisation paths. Compilation/runtime correctness remains pending the current GitHub Actions results.

#### Additional iOS memory-pressure candidate deliberately deferred

The iOS `SkiaAnimatedImage.ios.kt` renderer already observes `UIApplicationDidReceiveMemoryWarningNotification` and evicts idle decoded animations, but its separate in-memory compressed-source LRU (`MaxCachedSourceBytes = 24 MiB`) is not cleared. Its entries are reconstructable from the disk cache or, for `file://` sources, from the original file. Apple's guidance says caches contain transient/non-critical data that can be discarded and specifically recommends releasing cached media data that already has an on-disk representation in response to memory warnings:
- https://developer.apple.com/library/archive/documentation/Performance/Conceptual/ManagingMemory/Articles/CachingandPurgeableMemory.html
- https://apple-docs.everest.mt/docs/uikit/responding-to-memory-warnings/

No code change was made: the cache is protected by a coroutine Mutex and a correct purge needs to coordinate with in-flight loads and the notification's main-queue lifecycle. This remains a focused iOS follow-up with a required regression test, not an assumed one-line cache clear.


### Follow-up: diagnose failed repaired-source CI and correct remaining compile blockers (9 November 2026)

The first merge-compatibility repair (\`07f384473e89d38ae0d564e6cf5087802bbc4ee2\`) was not sufficient. The two current-head workflows subsequently completed and failed against validation commit \`aaebb02d04353c2473f516f33e92e11afc0747d0\` (whose source parent was \`07f3844\`). Their job logs were inspected; no new workflow, test task or build was started during this follow-up.

#### Failed validation evidence

- Linux run \`42541044666\` failed inside the Android full-host-test workflow step. Environment setup and Rust target setup had completed; the APK step was skipped after the test step failed during source compilation.
- iOS run \`42541044652\` passed Xcode 27.1 verification and the repository's iOS dependency-preparation script, then failed in the unsigned full Debug IPA build during Kotlin/Native compilation.
- The shared errors were dominated by a structural problem in \`LibraryScreen.kt\`: one missing closing brace kept subsequent library models, cache APIs, screen helpers and composables inside the \`LibraryScreen\` body. The compiler then reported hundreds of downstream unresolved/local-declaration diagnostics. This is a cascading parser/scope failure, not hundreds of independent missing APIs.
- The remaining independent causes were confirmed directly against the source:
  - \`SearchScreen.kt\` contained a duplicate query-blank results block. The first duplicate held a malformed \`discoverContent\` call; its cleanup had also left an extra closing brace and a duplicate \`Color\` import.
  - \`DownloadContentRange\` was declared in both common code and \`AndroidDownloadTransfer.kt\` in the same package, causing Android source-set redeclaration. The Android transfer already calls the common parser, so the Android duplicate was redundant.
  - The restored background implementation declares \`isGlassUiActive\` and \`Modifier.appGlassBackdrop\`, but \`BottomSheet.kt\` and \`SettingsScreen.kt\` had not imported those APIs.
  - \`SettingsBackgroundImagePicker.android.kt\` intentionally uses \`androidx.exifinterface.media.ExifInterface\` to preserve EXIF orientation. The dependency was absent from the current version catalog and \`androidMain\` configuration. The older October commits \`c2070236\`, \`c3815270\` and \`e739849d\` establish that EXIF orientation and picker-cancellation behaviour are intentional and must remain.

#### Implemented corrections

- Added the missing Library scope close immediately before the top-level \`cloudLibraryContent\` declaration. The library's saved controls, cloud search, calendar and release-schedule APIs remain intact; this makes those existing declarations visible at file scope again.
- Removed the duplicate SearchScreen results block and its malformed retry call, leaving one query-blank discovery results branch and the single discovery-filter row in the header. Removed the extra closing brace left after the earlier cleanup and deduplicated the \`Color\` import.
- Removed only the duplicate \`DownloadContentRange\` data-class declaration from Android. Android transfer now uses the existing common type/parser; transfer, range validation and resume logic are unchanged.
- Imported the existing \`isGlassUiActive\` and \`appGlassBackdrop\` helpers in their two consumers; no duplicate helpers or new background behavior were added.
- Added the stable AndroidX ExifInterface dependency \`androidx.exifinterface:exifinterface:1.4.2\` through the version catalog and the Android main source set. The existing picker continues to use AndroidX EXIF metadata to rotate/flip selected images. Stable 1.4.2 was preferred over the newer 1.5.0 beta listed in the official AndroidX release notes: https://developer.android.com/jetpack/androidx/releases/exifinterface .

#### Source review and verification status

- Code commit: \`fc8fbe1c0d2b9cb13b4d3c6b9fea13cb0760f505\` (\`fix: resolve Android and shared Kotlin CI compile blockers\`), a normal fast-forward from notes head \`e94a12ad2e34f012eddf04cab9272218f0e4d5f6\`.
- The complete comparison contains seven files: LibraryScreen, SearchScreen, AndroidDownloadTransfer, BottomSheet, SettingsScreen, \`gradle/libs.versions.toml\`, and \`composeApp/build.gradle.kts\`. No unrelated modules, UI resources, website-managed settings, signing configuration, Gradle/AGP/Kotlin versions or build workflows were changed.
- Static source checks performed on the candidate commit:
  - LibraryScreen and SearchScreen Kotlin brace/scope scans both finish at depth zero with no negative nesting.
  - Library helper/model declarations, including \`cloudLibraryContent\`, \`LibraryViewMode\`, calendar helpers and \`CloudLibrarySearchField\`, are top-level declarations again.
  - SearchScreen contains exactly one \`DiscoverFilterRow\` and one \`discoverContent\` call in its intended result branch, with one \`Color\` import.
  - \`DownloadContentRange\` is declared once in commonMain and no longer redeclared in AndroidMain.
  - The ExifInterface version alias, library coordinate and Android source-set dependency are present and consistent.
  - The two restored background helper imports match their actual declarations.
- No Gradle command, unit test, Android build, Xcode build or CI workflow was run after the failure. The failed runs above still describe commit \`07f3844\`, not \`fc8fbe1\`. Compilation, test execution, APK/IPA output and runtime behavior for the corrected source are therefore **unverified** pending explicit build authorization.
- The separate validation branch remains at \`aaebb02d04353c2473f516f33e92e11afc0747d0\`; it was not updated or re-triggered. \`enhanced\` was not changed.

The previously recorded iOS compressed-source memory-cache candidate remains deferred: it needs an in-flight-load-safe purge design and a focused test before implementation.

### Continuation: unblock Android host-test compilation and revalidate DNS regression (9 November 2026)

The latest Linux validation run against source snapshot `d8a5faf5fd13f62226fe17ef769233c1e07efeb3` failed before executing tests. The failing test sources were fetched from both that CI snapshot and the live `main-build-testing` head `ce981b1e378dfd76f4549a58814a4309ffecb788`; their blob IDs match exactly, so these are still real current-head blockers rather than stale CI-only errors.

#### Root causes and minimal test-harness corrections

- `FloatingNavigationBarGestureTest.kt` supplied a one-argument custom content lambda, but `FloatingNavigationItem.content` now accepts both the click callback and nullable default icon colour override. The test ignores the second argument, so the lambda now explicitly accepts it without changing the gesture scenario.
- `PlayerSurfaceGesturesTest.kt` called `playerSurfaceDragGestures` without the newly required nullable `playerController` and `swipeToSeekEnabledState` arguments. The fixture has no software player controller, so it passes `null`; swipe-to-seek remains enabled to preserve the scenario's previous intended default.
- `NavigationBarGlassSettingsTest.kt` used the removed `size.width`/`size.height` touch-scope API. The pinned Compose UI test receiver exposes `width` and `height` directly. Updated the test coordinates to those current properties, retaining the same 20%-to-80% swipe.

#### CI evidence

- Latest Linux run: https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/42544820358
- Job `128927408637` reached `:composeApp:compileAndroidHostTest` and reported these exact blockers in the three files above. The host-test task stopped at compilation; no tests ran. The fullDebug APK step was skipped.
- The DNS production source was identical between that run's commit and the current branch. The corrected CNAME fixture was subsequently committed to the live branch, but the resolver still only used the queried A/AAAA record TTL and therefore does not yet satisfy the existing 30-second CNAME regression.
- The repository clone could not be established in this execution environment because DNS resolution for `github.com` failed. Source inspection and remote Git operations are therefore performed against exact GitHub refs/blob IDs; a local dirty/clean working-tree claim is not available.

#### Status

- This commit only repairs Android host-test source compatibility; it does not change production code, test intent, app UI, player behavior, DNS settings, dependencies, Gradle configuration, iOS sources or build workflows.
- No test, Gradle task, Android/iOS build or CI workflow has been started in this step. The CNAME test remains expected to fail at runtime after the host-test sources compile; that RED result will be checked before changing the resolver.

### Continuation: complete Android DoH cache correctness (9 November 2026)

#### Implementation

- Updated the Android DoH parser to retain the minimum TTL across every resource record in the DNS Answer section while extracting only the requested A or AAAA addresses. A shorter CNAME therefore limits the combined address lifetime; zero TTL is preserved for the current lookup and no longer converted into a synthetic 60-second lifetime.
- Added HTTP `Age` handling. Absent Age is treated as zero; a valid non-negative Age is subtracted from the DNS TTL; malformed, negative, zero-lifetime and already-expired results are not cached. The expiry deadline is anchored to the monotonic request-start timestamp, so network/response transit time cannot extend the remaining lifetime. The existing 3,600-second upper bound is preserved and the unsafe 60-second lower bound is removed.
- Combined A/AAAA expiry uses the earliest deadline across successful responses with Answer-section records, including CNAME-only responses. The existing sequential A-then-AAAA request order and optional AAAA failure behavior remain unchanged; a successful response with Answer records but no valid remaining lifetime prevents caching the combined address list.
- Replaced the unbounded concurrent map with a synchronized access-ordered LRU cache capped at 256 entries. Expired entries are removed on read and pruned on insertion; network requests run outside the cache/provider lock.
- Added a provider-generation barrier. Provider changes clear the cache and advance its generation; an older in-flight request may still satisfy the lookup that started under its snapshot, but cannot repopulate the cache after the provider changes.
- Added deterministic tests for CNAME minimum TTL, zero/no-answer TTL, HTTP Age parsing and request-start expiry, expiry boundary, insertion pruning, LRU eviction/capacity and provider-generation invalidation.

#### Technical basis

- RFC 8484 §5.1 requires DoH clients to account for the HTTP Age field when calculating DNS TTL and specifies the smallest Answer-section TTL as the safe HTTP freshness ceiling: https://www.rfc-editor.org/rfc/rfc8484.html
- RFC 9111 §4.2.3 calculates corrected age as the Age value plus response delay and interprets that age relative to request initiation; §5.1 defines Age as a non-negative integer and says the first member should be used for a list-valued field: https://www.rfc-editor.org/rfc/rfc9111.html
- Existing Kotlin, Compose and OkHttp dependency versions are unchanged. The resolver remains Android-only; the common DNS repository and Android platform adapter are unchanged.

#### Verification state

- The production and regression-test diff is committed separately from the preceding host-test API repair.
- Source-level review confirms no new dependencies, storage, background jobs, parallel DNS queries, changes to provider names/defaults, system fallback, shared/iOS DNS implementation or website-managed settings.
- Full Android host tests and Android/iOS builds remain pending the dedicated validation workflow; no success is claimed until those workflows complete and their logs are inspected.

### Follow-up: repair JUnit4 discovery for the Android trailer probe test (9 November 2026)

The first full Android host-test execution after the source-compatibility repairs ran 2,330 tests and reported 29 failures. One was not an assertion failure in the reachability behavior: `TrailerReachabilityProbeTest` failed during JUnit class initialization with `InvalidTestClassError`.

The exact test class was inspected. Its first expression-bodied `runBlocking` test ended with `server.takeRequest(...)`, whose return value is a `RecordedRequest`; Kotlin therefore inferred a non-Unit return type for the annotated test method. JUnit 4's `FrameworkMethod.validatePublicVoidNoArg` explicitly rejects methods that return anything other than Java `void`: https://junit.org/junit4/javadoc/latest/org/junit/runners/model/FrameworkMethod.html

Added an explicit trailing `Unit` after the request wait. This preserves the request-arrival synchronization and all existing assertions while ensuring the generated test method returns void. No production trailer code, request behavior, timeout, dispatcher, dependencies or other test files were changed.

CI evidence: Linux run https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/42548131765 completed `:composeApp:testAndroidHostTest` with 2,330 tests / 29 failures. The JUnit initialization error was one of those failures. The 28 other failures are recorded separately; they do not point to this test's return signature. The focused test-source correction is committed, but still requires a fresh host-test run before it can be considered verified.

### Validation continuation and outstanding host-test failures (9 November 2026)

- The first executable Linux run, `42548131765`, used the source parent `749d817` (before the DoH resolver implementation). It completed 2,330 host tests and reported 29 failures. The CNAME regression was one of them, failing because the then-current parser returned the terminal A-record TTL instead of the shorter CNAME TTL. This is the intended RED evidence for that specific regression.
- The other 28 failures were in `RootTabHostTest`, `RunCatchingUnlessCancelledTest`, `BackendRateLimitTest`, `CustomPosterOverlayTest`, `LiquidGlassPreviewCornerRadiusTest`, `LiquidGlassSurfaceSettingsTest`, `RatingsVisibilityTest`, `AndroidDownloadLifecycleTest`, `DownloadSubtitlesTest`, `HomeHeroPagerTest`, `HomeHeroSectionTest`, `PluginSyncTest`, `AvatarPickerTest`, `SettingsPagesTest`, `StreamAutoPlaySelectorTest`, `TrailerReachabilityProbeTest`, `WatchedModelsTest`, `WatchProgressIdentityTest` and `WhatsNewSnapshotBuilderTest`. These are outside the DNS patch, but no earlier executable full-host-test run exists in this continuation, so they are **not proven pre-existing**; they require separate root-cause triage. The fullDebug APK step was skipped because the workflow gates it on the host-test task succeeding.
- The `TrailerReachabilityProbeTest` class-initialization failure was then traced to the non-Unit return inferred from the final `server.takeRequest()` expression and corrected in `e1b5340`. A fresh Linux run for that exact source head is `42549524953`; it was still in progress at the last status check.
- Full Linux validation of the resolver source at `a31dc16` is run `42548834840`. The unsigned iOS Debug IPA build against the same production-code commit is run `42548834869`. Both were still in progress at the last status check; no success is claimed. The later `e1b5340` change is Android-host-test-only, so it does not change the production Kotlin/iOS sources used by the iOS run.
- The validation workflows were copied into dedicated `ci/*` branches with exact branch filters. The target branch's normal PR APK workflow, iOS workflow, Gradle files, dependency versions, signing settings and release configuration remain unchanged.

A complete source diff was rechecked from the initial head `ce981b1e` through `e1b5340`. The seven changed paths are the Android DoH resolver and its host tests, three Android host-test API call-site corrections, the Android trailer test's explicit Unit return, and this engineering log. No common/iOS production source, UI, navigation, website-owned settings or dependency/build configuration changed. The `enhanced` integration branch remains at `89f7ae19`.

The current environment could not establish a local Git checkout because DNS resolution for `github.com` failed. Git operations and full-diff verification therefore used exact GitHub refs and compare results; a local working-tree cleanliness claim is not available.

### Follow-up: respect explicit Retry-After and snapshot poster artwork independently (9 November 2026)

A corrected resolver CI run (`42548834840`, source commit `a31dc16`) completed 2,340 Android host tests with 28 failures; all 11 `NuvioDnsTest` cases passed, including the CNAME minimum-TTL regression. The APK phase was skipped because the existing workflow gates it on a fully green host-test task. The separate run including the JUnit return-type correction (`42549524953`) was still in progress when this note was last updated.

Two unrelated failures were traced to concrete implementation/test mismatches:

- `BackendRateLimitTest.retry delay uses bounded fallback and adds jitter` expected a 60-second explicit `Retry-After` to remain 60 seconds, but `retryAfterDelayMillis` clamped every value to `MaximumFallbackDelayMs = 30 seconds`. The constant is explicitly the *fallback* cap; applying it to a valid server delay made the client send follow-up requests earlier than the server asked. RFC 9110 §10.2.3 defines Retry-After as the time the user agent ought to wait before a follow-up request. Updated the function so valid delta-seconds/HTTP-date directives are retained, while only invalid/missing-header fallback delays are capped at 30 seconds. The existing deterministic 60-second retry test is retained and a direct 60-second parser assertion is added. The server-provided delay continues to receive the existing bounded jitter in the retry path.
- `CustomPosterOverlayTest.withCustomPosterUrl_preserves_original_landscape_when_raw_poster_is_already_captured` exposed that a single `hasOriginalArtworkSnapshot` check treated either raw URL as proof both poster slots were already captured. When `rawPosterUrl` existed but `rawLandscapePosterUrl` was absent, the original add-on landscape URL was overwritten by a null snapshot. The overlay now captures each original artwork field independently before the first overlay. The explicit `customPosterOverlayApplied` flag still distinguishes a genuinely null original URL from a field not yet captured, so clearing an overlay restores null originals correctly.

No retries, mutation of unsafe POSTs, profile/storage behavior, poster resolver patterns, artwork ordering, UI, platform storage or dependencies were changed. Both fixes are common code, so they apply consistently to Android and iOS. The exact source/test diffs are committed for fresh host-test and platform compile validation; no result is claimed until those jobs finish.

Research: RFC 9110 §10.2.3 — https://www.rfc-editor.org/rfc/rfc9110.html (Retry-After semantics).

### Follow-up: align Liquid Glass regression assertions with the latest intended defaults (9 November 2026)

The Android host-test run against source `a31dc16` reported four Liquid Glass assertion failures. The latest implementation history was inspected before changing either code or tests:

- Commit `6a69dd59cc2f` (`fix(glass): align surface defaults with current guidance`) deliberately removed `CATEGORY_HEADER_TITLE` from the default LIQUID/refraction set so passive content remains FROSTED. The back-button control remains LIQUID. The title and back button still share the exact same geometry/recipe (radius, blur, refraction and tint); only their material differs by role. Updated the stale common test to assert that distinction while preserving shared recipe and lens-capability assertions.
- Commit `1b7d551dc8dc` deliberately changed the Library capsule to a full pill with `0.22f` tint and updated the runtime and recipe together. Updated the old `0.30f` test expectation to the current `0.22f`.
- The current `LiquidGlassSurfaceGroup.LIBRARY` explicitly includes `LIBRARY_CALENDAR` as an independently editable surface; the existing host test still expected the pre-calendar six-surface group. Updated it to include the calendar.
- The settings sheet exposes the icon-tint control whenever `surface.supportsIconTint` is true, and `CATEGORY_HEADER_BACK_BUTTON` has an actual arrow icon plus an enabled per-surface tint capability. Added it to the expected tintable set; no production setting or visual behavior changed.

These are test expectation corrections only. No Liquid Glass runtime code, saved settings, defaults, previews, geometry, native iOS materials, or UI behavior was modified. Source-of-truth history: commits `6a69dd59cc2f`, `1b7d551dc8dc`, and `440fa3e37b98`. The revised tests remain subject to a fresh full host-test run.

### Follow-up: prevent empty-server AddonRepository pulls from resurrecting deleted add-ons (9 November 2026)

The full host-test run exposed `PluginSyncTest.emptyRemoteRemovesCachedAddonsWithoutUploadingAndStaysEmptyAfterReload`. This was traced to a concrete regression in `AddonRepository.pullFromServer`: on the first empty server response it migrated the local add-on cache back to the backend via `sync_push_addons`; on later empty responses in the same process it preserved the local cache. Because `pulledFromServer` was only an in-memory boolean, a device restart reset the flag and could re-upload locally cached add-ons after another device had deleted them remotely.

This behavior directly contradicts the intended source-of-truth policy already established in repository history. Commit `1854dfc3c0db` (`fix(sync): prevent automatic pulls from restoring deleted data`) introduced the matching Android Full host test and removed these exact two empty-response branches from `AddonRepository` and the full-feature `PluginRepository`. Its test asserts that an empty remote result removes both UI and persisted local add-ons, remains empty after reload, and performs only the two GET pulls with no upload RPC. The current `main-build-testing` file had regressed by reintroducing the same local-migration/preservation branches and `pulledFromServer` flag during later merge/reconciliation work.

Restored the historical server-authoritative behavior for an empty successful response: the normal reconciliation now applies an empty add-on snapshot and persists it; no local cache migration or preservation branch is run, and no `sync_push_addons` is issued. Failed pulls still preserve cached state because the reconciliation is only reached after a successful decoded response. The newer profile operation token/generation gate, effective shared-profile resolution, cancellation handling, client capture, refresh cancellation and profile-specific persistence remain intact. No storage schema or backend RPC changed.

This is supported by the exact regression test and the original deletion commit, not an inferred preference. Verification is pending a fresh Android host-test run; the current validation run was started before this fix and cannot validate it.

### Follow-up: make the timeout/caller-cancellation test exercise structured cancellation (9 November 2026)

The first host-test run recorded `RunCatchingUnlessCancelledTest.caller cancellation is not converted to the timeout fallback` failing at its exception-identity assertion. Inspection showed the test constructed a `CancellationException` and threw it directly from inside `withTimeoutOrNull`, then asserted the same object identity after coroutine propagation. kotlinx.coroutines' `withTimeoutOrNull` only turns its own `TimeoutCancellationException` into null; parent/caller cancellation is propagated. Cancellation exceptions may be copied during coroutine propagation, so object identity is not a valid assertion for the caller-cancellation path. The separate `cancellation is rethrown unchanged` test already asserts identity at the helper boundary without a timeout coroutine.

Replaced the misleading test with a real structured-concurrency scenario: an async request enters `runCatchingUnlessCancelled`, signals a `CompletableDeferred` readiness barrier, suspends in `awaitCancellation()`, and is then cancelled by its caller with the specific message. The test awaits the child and verifies the cancellation exception propagates with that message instead of the timeout fallback. Production cancellation helper and timeout behavior are unchanged. This correction requires a fresh host-test run.
Source: kotlinx.coroutines 1.10.2 `Timeout.kt` (the pinned version in `gradle/libs.versions.toml`): https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Timeout.kt

#### Correction to the first empty-server patch attempt

The first remote edit used an overly broad end-marker search and removed part of `AddonRepository` beyond the two intended conditionals. The dedicated Linux compile run `42550747308` caught it at `:composeApp:compileAndroidMain` with unresolved repository state/method references; that run is explicitly invalid for behavior verification. The file has now been reconstructed from its exact pre-edit `e89f0e3` version, and only the two `urls.isEmpty()` branches are removed using matching conditional-block boundaries. Before commit, the complete source was checked to retain `profileOperationGate`, `persistSnapshot`, `activateProfile`, `awaitManifestsLoaded`, `addAddon`, `moveAddon`, `setAddonEnabled`, `refreshAll`, and the normal URL reconciliation path. This corrected patch is pending a fresh compile and host-test run.

### Follow-up: correct ambiguous watched-key and placeholder-duration test fixtures (9 November 2026)

Two more existing failures were caused by fixtures that contradicted the production parser/policy:

- `WatchedModelsTest` used `series:tmdb:123:1` as a supposedly malformed key with a missing episode. The current parser intentionally treats the final two numeric path segments as season and episode so provider IDs may contain colons. That string is therefore a valid key with content ID `tmdb`, season 123 and episode 1; it cannot also be interpreted as content ID `tmdb:123` with a missing episode. The test now uses a non-numeric final segment (`episode`) for the malformed case. Valid colon-delimited provider ID coverage remains unchanged.
- `WatchProgressIdentityTest` used a 1,000 ms duration with a 940 ms position while asserting completed playback. The current shared policy intentionally treats all media shorter than 121 seconds as placeholder/error clips, so this fixture cannot represent a completed episode. It now uses a 1,000,000 ms duration and 940,000 ms position, preserving the same 94% completion ratio and verifying the original position remains intact. Production completion thresholds and placeholder handling are unchanged.

Also aligned two release-note test expectations with the established product spelling `IMDb` used by `formatReleaseNoteForDisplay` (which already normalizes IMDB casing). No production release-note code changed.

### Follow-up: correct end-of-month “last checked” age buckets (9 November 2026)

The regression `lastCheckedTreatsEndOfMonthAsARealCalendarMonth` exposed that `Instant.periodUntil` can represent January 31 to February 28 as zero whole months plus 28 days. For a user-facing age label this is misleading: KotlinX Datetime 0.8.0's month arithmetic clamps an invalid target day to the last valid day of that month, so one calendar month has elapsed.

The formatter now calculates whole calendar months with `Instant.until(end, DateTimeUnit.MONTH, timeZone)`, derives whole years and remaining months from that count, and retains `periodUntil` for timezone-aware day/week/hour buckets. This preserves local DST semantics for day labels while treating end-of-month pairs consistently. The existing EOM, DST-boundary and leap-year tests cover these boundaries; no dependency or locale/string changes were introduced. Fresh CI verification is pending.
Source: KotlinX Datetime 0.8.0 source/docs for `Instant.until`, `DateTimeUnit.MONTH`, and month-end clamping: https://github.com/Kotlin/kotlinx-datetime.

### Sign-in by Nuvio profile/device link — build verification (9 November 2026)

The request to restore the older “Sign In with Link” experience was compared against the current source and its original implementation commit. The feature is still present; no production auth code was changed in this investigation.

#### Confirmed mobile flow

- Original implementation: `388e613c0bf2` (`feat(auth): add six-character login codes`, 28 August 2026). It adds the “Sign In with Link” button and the device-code view to both compact/mobile and large-screen AuthScreen layouts.
- The current `AuthScreen` only renders that button for sign-in mode and when `serverConnectionState.activeServer.capabilities.tvLogin` is true.
- The built-in official configuration explicitly sets `tvLogin = true`. A self-hosted configuration takes `tv_login` from its website-owned `/.well-known/nuvio` discovery document. Do not bypass that capability for custom backends: the backend must implement the matching device-login RPCs.
- `DeviceLinkAuthRepository` creates a nonce and calls `start_device_login_session`, displays the returned six-character user code, opens the server-provided `verification_uri_complete`, polls `poll_tv_login_session`, then invokes `tv-logins-exchange` and imports the returned access/refresh token pair using the Supabase Auth plugin.
- The official verification route is `https://nuvio.tv/link`; self-hosted setups use their configured backend's `/link` route. Approval occurs in the browser; this mobile flow does not depend on an app deep-link callback.
- The SDK version is pinned to Supabase Kotlin `3.4.1`. Its tagged `AuthImpl.importSession` saves a refreshable session when `autoSaveToStorage` is enabled; the SDK defaults `autoSaveToStorage`, `autoLoadFromStorage`, and `alwaysAutoRefresh` to true. `SupabaseProvider` installs `Auth` without overriding those defaults or supplying an in-memory-only session manager.
- Later auth hardening must remain in place: `c710d6568cc4` binds the device attempt to its originating Supabase client/backend; `6b3fd9160981` closes the start-gate race; `6c9ba4822a64` retains and awaits a cancelled device-link job during backend switching; and `14d392453054` drains pending auth-session imports before resetting the backend.

#### Why the link can be absent

The button is intentionally hidden when a custom server's discovery document sets `capabilities.tv_login=false`. This is server capability ownership, not an app-wide feature flag. The official server path enables it. A previously saved custom server configuration can therefore make the button disappear until the user switches back to the official Nuvio server or the custom server enables and implements device login. The app must not silently discard that saved configuration.

#### Build plan and limits

- Build from the exact current `main-build-testing` head, full distribution, using the live official `https://api.nuvio.tv/.well-known/nuvio` backend URL and publishable key as runtime build inputs, rather than hard-coding or copying a stale key.
- Produce Android fullDebug APK and unsigned iOS full Debug IPA. The normal PR/release workflow and signing configuration remain unchanged; validation triggers live only on a separate CI branch.
- A successful build proves compilation/package creation, not that the live backend's device-login RPCs or website approval page are available at the time of sign-in. Runtime approval still depends on the official website/backend.


### 9 November 2026 — targeted host-test and hero sizing follow-up

The historical Linux validation run for this branch family reached `:composeApp:testAndroidHostTest` and reported 15 failed tests; the APK task was gated after that test failure. A separate Android full-Debug APK run and an unsigned iOS Debug IPA run succeeded on their respective validation refs. These historical results are not a fresh validation of the current branch head.

Four narrowly scoped corrections were made after tracing the current source contracts:

- **Robolectric/JDK compatibility:** `AndroidDownloadLifecycleTest` now runs on API 34 only. The Linux host-test workflow uses Temurin JDK 17. Robolectric 4.16's compatibility table requires JDK 21 for API 36, while API 34 runs on JDK 17; API 34 is also the level that introduced `JobInfo.Builder.setUserInitiated`, which this test exercises. This changes only the test SDK matrix, not production JobScheduler behaviour.
- **Root-tab coverage:** the app's `AppScreenTab` enum includes Home, Search, Library, LiveTv and Settings. Four root-host test loops previously omitted LiveTv while the assertions expected every enum entry to have been visited. They now derive a stable traversal from the enum entries and visit Home last. No RootTabHost production code changed.
- **Portrait hero viewport reserve:** the current portrait hero ratio (1.5 times available width) is retained when no lower-section reservation is supplied. When HomeScreen supplies its existing Continue Watching/native-navigation reserve hint, the portrait height now goes through the same available-viewport cap used by landscape layouts instead of returning early. The no-reserve baseline test now expects 585 dp at 390 dp width; existing reserve and short-viewport assertions remain the intended constraints.
- **Download subtitle concurrency:** the Android scheduler starts subtitle preparation as a child coroutine, starts the video transfer without awaiting it, then joins subtitle preparation before finalising the completed download. The test no longer requires the video request to be the last HTTP request; it verifies that the video and both subtitle requests each occur and that the completed download contains both local subtitle tracks. This aligns the test with the existing non-blocking scheduler change; production download behaviour is unchanged.

#### Approaches deliberately not promoted

- Do not clear the compressed-source `LinkedHashMap` in the iOS animated-image renderer merely because a memory warning arrives. Apple’s WWDC18 memory guidance explains that traversing/mutating dictionary-backed caches can fault compressed pages back into memory and temporarily worsen pressure. The proposed one-line purge therefore lacks a safe memory-benefit guarantee. Revisit only with measured memory-pressure evidence and a cache policy that avoids this decompression hazard.
- No production changes were made for the remaining historical failures in ratings visibility, avatar-grid bounds, hero-pager wrap timing, settings navigation or stream add-on prioritisation. Their exact failure causes were not sufficiently established from the available historical job summary/source alone; do not weaken assertions or change UI behaviour speculatively.
- The earlier device-link sign-in conclusion is unchanged: the feature remains server-capability-gated and the investigation did not establish a reason to modify auth code or build configuration.

#### Verification limits

The changes were checked against the current branch source, its test fixtures, the historical commit that introduced concurrent subtitle preparation, the current Linux workflow's JDK version, and the pinned Robolectric version. The complete generated commit diff was reviewed before the guarded branch update; the updated remote head will be fetched again after publication. No Gradle build, host test, test-triggering command or CI workflow was started because build/CI execution remains explicitly unauthorised. Fresh test/build results are therefore pending.

#### Source references

- Robolectric 4.16 Android API/JDK matrix: https://robolectric.org/compatibility_table/
- Android API 34 additions to `JobInfo.Builder` (`setUserInitiated`): https://developer.android.com/sdk/api_diff/34/changes/android.app.job.JobInfo.Builder
- Apple WWDC18, *iOS Memory Deep Dive*: https://developer.apple.com/videos/play/wwdc2018/416/


### 9 November 2026 — restore live official backend configuration in Android/iOS build workflows

The screenshot's `Link sign-in failed.` state maps to `DeviceLinkAuthFailure.Start` or `Complete`. The app still renders Device Link sign-in when the active server advertises `tvLogin`; the official configuration hard-codes that capability on. The failure is raised after the device-link flow calls `start_device_login_session`, polls `poll_tv_login_session`, and invokes `tv-logins-exchange`.

The workflow/build data flow identifies a concrete configuration regression:

- `composeApp/build.gradle.kts` reads `NUVIO_SUPABASE_URL`, `NUVIO_SUPABASE_ANON_KEY`, and `NUVIO_SUPABASE_FALLBACK_URL` from `local.properties` (with environment-variable fallback), then generates `SupabaseConfig.kt`.
- `ServerConfiguration.officialConfiguration()` uses those generated constants as the official server's backend URL and publishable key.
- `SupabaseProvider.createClient()` creates the runtime Supabase client from that active server configuration. Device Link therefore uses the build-time URL/key when the user is on the official server.
- The current iOS IPA and Android PR full-debug workflows only decoded `NUVIO_LOCAL_PROPERTIES_BASE64`; they did not remove or refresh the Supabase properties. A stale value in that repository secret could consequently be packaged even though compilation succeeded.
- The Linux validation workflow already removed these three properties and fetched `https://api.nuvio.tv/.well-known/nuvio` to inject its current `backend_url` and `publishable_key`.

The older CI branch `ci/liquid-glass-builds-restored-20261031` contained this same live-discovery preparation in both IPA and Android full-debug workflows. Its 1 November 2026 Android fullDebug run (41265247568) and unsigned full Debug IPA run (41265247590) both completed successfully, including their `Prepare/Configure runtime properties` steps. This is evidence for the workflow pattern, not a runtime sign-in test.

#### Change applied

Restored the live official discovery step in the current `.github/workflows/ios-test-build.yml` and `.github/workflows/pr-full-debug-build.yml`:

- Decode the properties secret when present, otherwise create an empty `local.properties`.
- Remove stale SDK/signing and Supabase URL/key/fallback entries before writing official values.
- Fetch the official discovery document with bounded retries/timeouts.
- Require non-empty backend URL and publishable key before proceeding.
- Write the current URL/key and explicitly clear the stale fallback URL.
- Preserve unrelated service credentials; the iOS workflow also preserves its current `CONTRIBUTIONS_URL` override.

No authentication repository, UI, Supabase SDK, server discovery semantics, saved custom-server preference, signing, or release code was changed. Stored custom-server configuration still takes precedence at runtime; these values define only the built-in official-server default.

#### Verification limits

The complete workflow diff and configuration data flow were reviewed. No Gradle build, host test, APK/IPA build, or GitHub Actions run was started. The corrected workflows therefore remain unverified by a fresh package build or a real device-link approval. The next authorised runtime check should install the newly produced Android APK and IPA, select the official server, start Device Link sign-in, and confirm that the six-character code is issued and the browser approval completes.

Sources:
- Current official server discovery: https://api.nuvio.tv/.well-known/nuvio
- Earlier successful workflow source: `ci/liquid-glass-builds-restored-20261031`
- Earlier Android build run: https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/41265247568
- Earlier iOS IPA run: https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/41265247590


### 9 November 2026 — bound iOS animated-avatar source loading

The shared AvatarPicker enables the iOS NuvioAsyncImage animated path. Before this change, SkiaAnimatedImage.ios.kt requested remote GIF/WebP data using body<ByteArray>(), which Ktor documents as loading the entire response into memory. The renderer had bounded decoded-frame memory (20 MiB per animation / 120 MiB shared) and a 24 MiB compressed-source cache, but those limits applied only after the full source had been allocated. Six source loads could be active concurrently, and the dedicated Darwin HttpClient had no explicit request or idle-read timeout. The same path read file:// assets and prior disk-cache entries completely before applying any source-size policy.

#### Change applied

- Added an 8 MiB per-source ceiling for animated image data.
- Replaced the unbounded HTTP ByteArray body conversion with a ByteReadChannel read capped at the configured limit plus one sentinel byte. This catches missing or inaccurate Content-Length headers.
- Reject a declared oversized response before reading its body and cancel its HttpResponse; an unknown-length/over-limit response is also cancelled immediately after the sentinel proves it is too large.
- Configure Ktor HttpTimeout on the Darwin client with a 20-second whole-request timeout and a 10-second socket-idle timeout. No connect timeout is set because Ktor documents that Darwin does not support that timeout.
- Check local file attributes before loading an animated source and validate disk-cache file sizes before reading them. Oversized old cache entries are removed. Source-cache insertion also refuses data above the limit.
- Distinguish an oversized source from ordinary fetch/decode failure. Oversized sources do not fall through to Coil's normal image request, which would otherwise issue a second unbounded download; other failures keep the existing Coil fallback.
- Added common tests for exact-limit acceptance, one-byte-over rejection and empty responses.

No visual styling, avatar entitlement, cache key, animation frame budget, lifecycle policy, image decoder, Android image-loading path, website-managed setting, dependency, or player behavior was changed.

#### Evidence and source checks

- Pinned project versions: Ktor 3.4.1, Coil 3.6.3, kotlinx-coroutines 1.10.2.
- Ktor's [response documentation](https://ktor.io/docs/client-responses.html) explicitly warns that body() loads the entire response into memory and recommends ByteReadChannel streaming for large responses.
- Ktor's [timeout documentation](https://ktor.io/docs/client-timeout.html) documents request/socket timeout behavior and states that Darwin supports request and socket timeouts but not connect timeout.
- Ktor 3.4.1 [DarwinTaskHandler](https://github.com/ktorio/ktor/blob/3.4.1/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/internal/DarwinTaskHandler.kt) exposes the response body as a channel, and [DarwinSession](https://github.com/ktorio/ktor/blob/3.4.1/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/internal/DarwinSession.kt) cancels the underlying NSURLSessionTask when its call context is cancelled. This supports cancelling the response after the size limit is reached.

#### Verification state and remaining limitation

The code/test diff and exact Ktor 3.4.1 source contracts were reviewed. The new common tests have not yet been executed, and the iOS IPA has not yet been built. Ktor 3.4.1's Darwin delegate uses an internal Channel.UNLIMITED queue for callbacks already received from NSURLSession; cancelling after the byte sentinel prevents continued application-level reading and cancels the network task, but cannot prove that no already-queued native chunks are transiently resident. This is an upstream engine buffering limitation, not a claim that the network stack has a strict byte-perfect peak-memory ceiling.

### 9 November 2026 — non-blocking release-notification formatting and bounded Android artwork

#### Findings and scope

The shared episode-release notification body builder resolved Compose Multiplatform string resources through `runBlocking`, even though its only production caller, `buildRequestsForShow`, already runs inside the suspend refresh pipeline and its structured `coroutineScope`. This blocked the calling dispatcher for resource I/O. The Android notification path also fetched backdrop images with Ktor `body<ByteArray>()`, which materializes the complete response, and passed the encoded bytes to a full-resolution bitmap decode. Concurrent notification construction could therefore amplify memory use for large or malformed artwork.

#### Changes

- Made `buildEpisodeReleaseNotificationBody` suspend and removed its `runBlocking` wrapper. Resource IDs, locale lookup, argument order, whitespace handling and fallback text remain unchanged; the production caller now uses an explicit suspend loop over episode metadata.
- Generalised the existing iOS animated-image ByteReadChannel limiter into a shared image-source reader, preserving its one-byte sentinel and exact-limit behavior. The iOS caller uses the new shared primitive with the same 8 MiB policy.
- Bounded Android notification backdrop responses to 8 MiB, rejected declared oversized responses before reading, and cancelled unknown-length responses immediately after the sentinel confirms overflow.
- Limited simultaneous Android backdrop loads to four. Bitmap decoding first probes dimensions, then applies power-of-two sampling to cap the decoded image at 1,280 pixels per edge and 1,048,576 pixels total. Invalid/non-success/oversized images retain the existing text-style fallback.
- Preserved cancellation through image loading and per-notification construction rather than treating cancellation as an ordinary artwork failure.
- Added common tests for localized body branches, image sizing boundaries and the shared byte-channel reader.

The separate synchronous `runBlocking` calls in Android notification-channel initialization were intentionally left unchanged: that non-suspending platform initialization API needs a separate lifecycle design. No schedule times, profile keys, account cleanup, deep links, channel settings, UI appearance, website-managed settings, dependencies or build workflows changed.

#### Evidence

- Kotlin's [runBlocking API documentation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html) warns that calling it from a suspend function blocks the caller thread and ignores its current coroutine context.
- Compose Multiplatform's [StringResources implementation](https://github.com/JetBrains/compose-multiplatform/blob/master/components/resources/library/src/commonMain/kotlin/org/jetbrains/compose/resources/StringResources.kt) defines `getString` as a suspend API for non-composable resource access.
- Ktor's [response documentation](https://ktor.io/docs/client-responses.html) states that `body()` loads the full body into memory and recommends streaming via `ByteReadChannel`.
- Android's [BitmapFactory.Options reference](https://developer.android.com/reference/android/graphics/BitmapFactory.Options) documents `inJustDecodeBounds` dimension probing and `inSampleSize` subsampling to save memory.
- Pinned dependencies remain Kotlin 2.4.10, kotlinx.coroutines 1.10.2, Compose Multiplatform 1.13.0-beta01 and Ktor 3.4.1.

#### Verification limits

Source and test changes were statically reviewed against the pinned APIs and prior iOS bounded-reader implementation. No tests, Gradle tasks, builds or CI were run or started, as instructed. The image tests cover the bounded byte reader and sample-size policy but do not replace an Android runtime bitmap/network test. An authorised host-test and Android/iOS build pass remains outstanding.

### 9 November 2026 — preserve cancellation and retry notification backstop failures

The Android periodic episode-notification worker previously wrapped its suspending delivery operation in `runCatching` and then always returned `Result.success()`. This captured coroutine cancellation and also classified every ordinary delivery/persistence/platform exception as completed work. The periodic unique work is the three-hour backstop, so a transient exception could otherwise postpone its next normal attempt rather than asking WorkManager for a retry.

The worker now rethrows `CancellationException`, preserving `CoroutineWorker` stop/cancel behavior, returns `Result.retry()` for ordinary exceptions so WorkManager applies its configured backoff, and returns success only after delivery completes normally. The unique work name, KEEP policy, three-hour interval, notification scheduler and primary alarm path are unchanged.

Evidence:
- Android's [CoroutineWorker threading documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/threading/coroutineworker) states that WorkManager stops CoroutineWorker by cancelling its coroutine.
- Android's [work request and retry documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) documents `Result.retry()` and retry backoff.
- The repository pins WorkManager 2.10.3 in `gradle/libs.versions.toml`.

Verification: source and return-path review only. No worker test, Gradle task, build or CI was run/started. Runtime scheduling and retry timing remain unverified.


### 9 November 2026 — conservative odd-dimension notification bitmap sizing

A final boundary review tightened the notification bitmap sample-size calculation to use ceiling division rather than floor division. This keeps odd image dimensions within the configured edge/pixel budget even when a decoder rounds a sampled dimension upward. Added an odd-dimension regression case (2,561 × 1,081). Static review only; the test remains unexecuted until builds/tests are authorised.

### 9 November 2026 — restore central Wi-Fi admission for downloads

#### Regression traced to original feature history

The repository had retained the device-level `allowMobileDataDownloads` preference, platform `DownloadNetworkGuard` actuals, `RequiresWifi` result and localized message, but its two enforcement checks had disappeared. The original 29 August 2026 commit `d01b37dadf6ced565f0f772ed436dd544ef80332` explicitly gated both `enqueueFromStream()` and `resumeDownload()`. Current callers bypassed the guard at enqueue, so the settings copy promised Wi-Fi-only starts while transfers could still start on cellular. Resume and retry also had no feedback when a network gate applied.

#### Changes

- Restored the Wi-Fi/mobile-data policy at the common `DownloadsRepository` boundary. It loads the device-scoped preference and checks the platform transport before replacing an existing item, cancelling its prior task or persisting a new download.
- Restored the same policy for paused/failed resume and retry. These methods now return `RequiresWifi` (or `Started` on success), and the downloads screen shows the existing localized explanation instead of silently doing nothing.
- Kept both direct streams and debrid-resolved streams on their existing shared enqueue path, so they cannot diverge in policy.
- Converted `DownloadEnqueueResult.toastMessage()` to a suspend resource lookup. The stream-selection download handler now runs both direct and debrid flows in one existing screen-owned coroutine scope, avoiding the former nested launch and removing `runBlocking` from this user-triggered message path.
- Added common tests for the network admission truth table and the localized enqueue-result messages.

#### Deliberate boundaries and residual behavior

The network check is an admission-time snapshot. Android's current `ConnectivityManager.getNetworkCapabilities(activeNetwork)` can become stale immediately, and Apple documents `SCNetworkReachabilityFlagsIsWWAN` as advisory rather than a strict promise about the eventual interface. The iOS background `NSURLSessionConfiguration` still allows cellular access, and Android background schedulers still use their existing connected-network policy. This change restores the documented "only start on Wi-Fi unless opted in" admission behavior; it does not claim that an already-started background task can never migrate onto cellular after the transport changes. Strict per-request or background transport enforcement needs a separate design that coordinates session configuration, pause/resume data, user preference changes, and platform scheduler semantics. No speculative scheduler/session migration was introduced.

The existing device-owned setting and platform storage were retained; no profile sync, website setting, migration, UI relocation, dependency, or schedule change was made. The pre-existing iOS unknown-total `Content-Range: */*` handling remains deferred because accepting an unknown total safely requires validating subsequent range segments and finalization, not merely removing the existing check.

#### Evidence

- Original implementation: `d01b37dadf6ced565f0f772ed436dd544ef80332` (`feat(downloads): require Wi-Fi for downloads unless mobile data is allowed`), whose diff added the exact common enqueue/resume gate.
- Apple [Networking Overview](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/NetworkingOverview/Platform-SpecificNetworkingTechnologies/Platform-SpecificNetworkingTechnologies.html) warns that `SCNetworkReachabilityFlagsIsWWAN` is advisory and recommends request-level cellular restrictions when that restriction must be strict.
- Apple [Tech Talk 111378](https://developer.apple.com/videos/play/tech-talks/111378/) discourages preflight reachability checks as a guarantee because network paths change during requests.
- Android [Read network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state) and [NetworkCapabilities](https://developer.android.com/reference/android/net/NetworkCapabilities) documentation describe dynamic transport capabilities and warn that instantaneous snapshots can go stale.
- Android and iOS actuals and storage contracts remain unchanged.

#### Verification state

Source, caller wiring, original feature diff and test additions were statically reviewed. New tests have not yet been run. No build or CI has been started yet.

#### Build execution limitation — 9 November 2026

A single Android/full mobile build was authorised after source review. The connected GitHub integration can inspect workflow definitions/runs/logs and rerun an existing failed job, but exposes no workflow-dispatch action; no Codemagic build runner is connected. An isolated temporary checkout was attempted, but the container could not resolve `github.com` (DNS failure), so there was no source tree available for a local Gradle invocation. No build or CI run was started. Do not interpret the absent run as a passing build. The next available execution path is a GitHub Actions workflow-dispatch-capable connector or a runner with a checkout of this exact branch head.

### 9 November 2026 — Android CI build attempt on the Wi-Fi download fix

The earlier note about no available CI dispatch route was corrected after reviewing previous `ci/nuvio-main-build-testing-*` branches. The established procedure is to create a dedicated `ci/...` branch from the exact candidate head and add a branch-scoped `push` trigger to `.github/workflows/pr-full-debug-build.yml`. This keeps the CI-only trigger outside `main-build-testing` while the workflow checks out the exact candidate source tree.

- Candidate application/docs head: `729f98b9c19aae28010007e6b6dafe5cb89eca1b`.
- Trigger-only CI branch: `ci/nuvio-main-build-testing-wifi-download-apk-20261109`.
- CI commit: `c920f815521185fc294fd55a8ee909e61bf4fbc3`. Its only changed file is `.github/workflows/pr-full-debug-build.yml`, adding a `push` filter for that exact CI branch.
- Workflow run: [42568408099](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/42568408099), workflow `PR Full Debug Build`, run number 108.
- Attempt 1 failed at `./gradlew :androidApp:assembleFullDebug` before Gradle started: the wrapper received HTTP 504 fetching `https://github.com/gradle/gradle-distributions/releases/download/v9.8.2/gradle-9.8.2-bin.zip`.
- A targeted retry of that same failed job (attempt 2, job `129000294758`) returned the same HTTP 504 from the same distribution URL. Java, Gradle setup action, runtime properties and Rust toolchain steps completed; the Gradle wrapper could not download its distribution.
- No Kotlin compilation, Android compilation, tests or APK upload ran. No APK artifact exists. This is an external distribution-download failure, not evidence of an application compilation defect.
- No further identical retries should be made until the Gradle distribution endpoint/network path is known to have recovered or an independently verified alternative source is available. The CI branch is retained for a later retry and does not alter the application branch.


### 9 November 2026 — fail-closed Android package-install permission check

The full-build updater's `canRequestPackageInstalls()` wrapper previously returned `true` when the platform call threw `SecurityException`. That converted an inability to verify install-source trust into apparent user approval. It now returns `false` on that exception, preserving the existing unknown-sources explanation/settings path and ensuring the installer is not launched on an unverified permission state.

Evidence:
- Android's [PackageManager API reference](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()) defines this method as checking whether the calling package is allowed to request installs; its result is true only when the user trusts the app as an install source.
- The full-build manifest declares `REQUEST_INSTALL_PACKAGES`; the Play Store target has `AppUpdaterPlatform.isSupported = false` and is not changed by this fix.
- The previous catch branch was a fail-open fallback, not a documented permission-grant path. Denial is the safe result when the platform cannot confirm permission.

Verification: reviewed the common controller's existing false-result flow (`showUnknownSourcesDialog` and `openUnknownSourcesSettings`), Android full/Play Store actuals, manifest declaration and complete changed-line diff. Static source review only; no tests, Gradle tasks, builds or CI were run.


### 9 November 2026 — restore platform TLS validation for Android playback

#### Root cause and affected paths

The Android playback networking helper installed a permissive `X509TrustManager` whose server-certificate check always succeeded, plus a `HostnameVerifier` that always returned true. That disabled both certificate-chain trust and hostname matching for every HTTPS request made through the shared playback OkHttp client. The same trust-all `SSLContext` was also installed on `HttpsURLConnection` for OpenSubtitles hash HEAD/range requests.

The bypass was introduced in commit `2f74c3cab04b724f0a080fac794343c314411fb8` as part of moving the player from Media3's `DefaultHttpDataSource.Factory` to the shared OkHttp-based helper for Nuvio TV parity. The previous data-source implementation did not override TLS verification. The current helper is used by the Android full and Play Store ExoPlayer data-source factories, the add-on subtitle routing path, and OpenSubtitles file hashing.

#### Change applied

- Removed the trust-all `X509TrustManager`, permissive hostname verifier and custom trust-all `SSLContext`.
- Removed both TLS overrides from the shared OkHttp client and the `HttpsURLConnection` path. OkHttp and Android now apply the normal platform certificate-chain and hostname verification.
- Preserved IPv4-first DNS, connection/read/write timeouts, local loopback read-timeout extension, redirects, connection retry policy, diagnostic interceptor, default User-Agent, sanitized request headers and HTTP cleartext compatibility. No player engine selection, source routing, UI, settings, or dependency was changed.
- The Android app manifest already references `@xml/network_security_config`; that configuration retains system CA trust and its existing additional ISRG roots. Those trust anchors remain available under the platform verifier.

#### Independent evidence and compatibility assessment

- The exact implementation history confirms this was a broad networking refactor rather than a documented per-provider trust exception: the earlier `DefaultHttpDataSource.Factory` did not install custom TLS trust or hostname validation.
- OkHttp's [Builder TLS API documentation](https://square.github.io/okhttp/4.x/okhttp/okhttp3/-ok-http-client/-builder/ssl-socket-factory.html) says system defaults are used when no custom socket factory is set and recommends most applications use those defaults. OkHttp's [Builder hostname-verifier API](https://square.github.io/okhttp/4.x/okhttp/okhttp3/-ok-http-client/-builder/hostname-verifier.html) likewise uses its default verifier when no override is provided.
- Android's [Network Security Configuration documentation](https://developer.android.com/privacy-and-security/security-config) explains that secure connections normally trust pre-installed system CAs and that app-specific additional trust anchors are configured declaratively.
- Android's [Media3 network-stack documentation](https://developer.android.com/media/media3/exoplayer/network-stacks) confirms the player receives the selected HTTP stack through its injected `DataSource.Factory`; no Media3 requirement calls for disabling TLS verification.

The security benefit is substantial: a network attacker can no longer impersonate an arbitrary HTTPS stream host with any certificate and intercept or alter HTTPS playback/subtitle traffic. The deliberate compatibility trade-off is that HTTPS sources with expired, self-signed, untrusted or hostname-mismatched certificates will now fail instead of silently accepting an unauthenticated endpoint. HTTP-only sources remain supported as before. No automatic insecure fallback or new setting was added; such a fallback would preserve the vulnerability.

#### Verification state

Reviewed all production references to `PlayerPlaybackNetworking` across the Android player factories, subtitle route and OpenSubtitles hasher; checked the full networking helper, both distribution factories, merged app manifest and network-security XML; compared the introducing commit and previous Media3 implementation; and inspected the OkHttp/Android API contracts. The change removes only the TLS bypass. No Gradle task, test, build or CI has run at the time of this note. Runtime HTTPS compatibility and both Android distribution builds remain to be verified.


### 9 November 2026 — correct two suspend resource builders exposed by Android fullDebug compile

#### Build evidence and root cause

The first Android fullDebug run against candidate `9329a34a1c33164ba8058532f0708cc4e75a5483` (workflow run [42602503090](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/42602503090), run 109) passed checkout, Java, Gradle setup, live runtime configuration and Rust toolchain setup, then reached Gradle/Kotlin compilation. It failed specifically at `:composeApp:compileAndroidMain` with two compiler diagnostics:

- `DownloadsModels.kt:105:5 Missing return statement`.
- `EpisodeReleaseNotificationsModels.kt:134:1 Missing return statement`.

The errors were present in the candidate's parent commit too; neither file was modified by the Android TLS change. The compiler output identifies them as source compilation failures, not the previous Gradle distribution HTTP 504.

Both errors came from removing `runBlocking` while converting a synchronous localized message builder into a suspending resource lookup, then leaving the `when` expression as a statement inside a block-bodied function. The notification helper had the same issue after its conversion to `suspend`. A final expression inside a block body does not implicitly return the function result.

#### Correction applied

- `DownloadEnqueueResult.toastMessage()` now explicitly returns its localized `when` expression.
- `buildEpisodeReleaseNotificationBody()` now explicitly returns its localized `when` expression.
- Existing message branches, resource IDs, formatting arguments, trimming and generic fallback remain unchanged.
- Existing common tests already cover all five download result messages and all four episode notification body branches; no duplicate tests were added.

#### Verification and next build

Confirmed both offending source blobs were identical at `9329a34` and its parent, read the two previous implementation diffs that introduced the suspend conversions, checked their production callers and existing common test coverage, and extracted both exact compiler diagnostics from workflow job 129110154795. The source correction is limited to the two missing `return` keywords. The failed run produced no APK; a fresh Android fullDebug build is required to confirm compilation and packaging after this fix. The first failure was not retried unchanged.


### 9 November 2026 — second Android fullDebug validation after suspend return fixes

The corrected source candidate is `13e802b384d06e5abe04193a73bd7f3c58c84a43` (`fix: return localized suspend resource messages`). A fresh CI branch was created from that exact source SHA: `ci/nuvio-main-build-testing-suspend-return-fix-apk-20261109`. Its sole additional commit is `00f2cf79e347042fd15f7812a9401fc0f6c8d186`, which adds only a push trigger scoped to that CI branch in `.github/workflows/pr-full-debug-build.yml`. Comparing the CI branch with `main-build-testing` confirms one commit ahead, zero behind, and only that two-line workflow change.

- Workflow run: [42604148116](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/42604148116), run 110, event `push`, candidate source commit `13e802b384d06e5abe04193a73bd7f3c58c84a43`.
- Job: `129115658681` (`Build fullDebug APK`).
- Checkout, Java setup, Gradle setup, live runtime properties and Rust setup completed successfully.
- At the latest check for this note, `:androidApp:assembleFullDebug` remained in progress. The job log endpoint returned `BlobNotFound` while the job was active, and no artifact was listed. Therefore this candidate is not yet build-verified and no APK is confirmed.
- Do not retry while this run remains active. Once it completes, inspect its conclusion, archived Gradle diagnostics and artifact list before deciding the next action.

The application branch remains at `13e802b384d06e5abe04193a73bd7f3c58c84a43`. The CI trigger is isolated on the dedicated CI branch and has not been merged into application source. A local checkout was not available through the connected GitHub interface, so local Git status remains unverified.

## Startup and first-screen latency — duplicate profile hydration and main-thread cache reads (10 November 2026)

### Root cause confirmed in the current branch

Android `MainActivity` awaits `StartupPreloader.awaitReady()` before installing Compose content. The preloader calls `ProfileRepository.loadCachedProfiles()` before the app is displayed. The owning `AppGate` then independently called the same method again in its own startup effect, even on Android. That second hydration reread and deserialized the same profile snapshot, reapplied it, walked the per-profile PIN cache in `applyStoredPayload`, and reloaded ThemeSettingsRepository from platform storage. The app's preloader completion is already a hard barrier, so this duplicate had no readiness benefit.

The profile cache loader also called `ProfileStorage.loadPayload()` and decoded the stored JSON synchronously before its first suspension. `loadCachedProfiles()` is invoked by the Android startup preloader on the main dispatcher; `ensureLoaded(userId)` is invoked by AppGate's auth/profile effects. Their storage/decode work therefore ran on the caller context even though both methods are suspend functions and the repository already uses `Dispatchers.IO` for PIN-cache synchronization and local biometric cleanup.

### Minimal implementation

- `StartupPreloader` now records whether its profile-cache step completed without an exception. The step wrapper returns success/failure while preserving cancellation propagation and the existing per-step error isolation.
- AppGate consults that process-startup result before calling `loadCachedProfiles()`. Android skips the second profile hydration because its preloader has already completed it. iOS does not call the Android Activity preloader, so the flag remains false and AppGate retains its original one-time cache-load behavior. A failed preload leaves the flag false so AppGate can retry.
- `ProfileRepository.loadCachedProfiles()` and `ensureLoaded(userId)` now perform the synchronous storage read and JSON decode inside `withContext(Dispatchers.IO)`. A shared Mutex serializes those suspendable cache loads; a generation token invalidated by `clearInMemory()` prevents a read that was suspended during account/profile cleanup from publishing stale state. StateFlow publication, selection state and ThemeSettingsRepository updates remain on the caller context.
- The account-mismatch cleanup of profile PIN cache entries now runs on the same IO context as biometric credential removal. The account-owner comparison, local lock cleanup, active-profile selection and biometric disable behavior are unchanged.
- Added common tests for both AppGate decisions and for accepting/rejecting profile-cache load generations, including invalidation after `clearInMemory()`.

### Why broader preload changes were rejected

The preloader's other steps were not bulk-removed. The October startup optimization previously allowed UI exposure while the repository preload still ran on IO; its follow-up explicitly reverted that after the same non-thread-safe `ensureLoaded()` methods could run concurrently from Main and IO. Also, profile selection invokes many repositories' `onProfileChanged()` hooks, so moving their local reads later can move synchronous storage work onto the UI path. Removing those steps without redesigning and verifying all profile-switch callers would trade a predictable preload for late work and possible races.

Likewise, the existing Android Baseline Profile generator already covers startup and Home scrolling, and the release app already merges generated Baseline Profiles. No duplicate profile generator, dependency, App Startup library, or new startup service was added. The current Liquid Glass rendering and image-loader architecture were left intact; Coil's guidance already recommends one shared ImageLoader, which Android provides through `NuvioApplication`.

### Evidence and trade-offs

- Android's official [app startup best practices](https://developer.android.com/topic/performance/appstartup/best-practices) recommend delaying work that is not required for the first frame and emphasize keeping initial composition lightweight.
- JetBrains' [Compose Multiplatform benchmark README](https://github.com/JetBrains/compose-multiplatform/blob/master/benchmarks/multiplatform/README.md) defines a STARTUP mode that reports time to first frame, first-frame duration and subsequent startup frames. That is the right measurement path once device/build profiling is authorised; source inspection alone does not quantify the gain.
- Coil's [ImageLoader documentation](https://github.com/coil-kt/coil/blob/main/docs/image_loaders.md) says one shared ImageLoader avoids per-loader memory caches, disk caches and HTTP clients. The app already follows this pattern, so no loader rebuild was warranted.
- Kotlin Multiplatform already uses `Dispatchers.IO` for profile PIN-cache and biometric storage operations in this repository; the change reuses the project's existing coroutine and dispatcher APIs. No compiler, dependency or platform API changes were introduced.

### Verification boundary and next step

Source-level review confirmed the Android call order (preloader awaited before `setContent`), the duplicate AppGate call, the profile storage/decode call sites, existing IO usage, and account-cleaner invalidation via `ProfileRepository.clearInMemory()`. The new common tests were added but not executed. No Gradle build, test task, CI workflow, simulator, emulator or device profile was started, as required. This is a justified reduction in duplicate work and main-thread I/O by code-path analysis; the exact cold-start improvement remains unmeasured.

Next, when authorised, capture repeated cold-start traces on a physical Android device and a representative iPhone. On Android use a release-like profileable build and the existing Baseline Profile journey; report process start-to-first-frame, first useful Home content, main-thread I/O slices, and P50/P90 over multiple cold launches. Do not claim a percentage gain from static source inspection.

### Image loading — de-duplicate in-flight poster fetches (10 November 2026)

The poster shelf path already prefetches the next six items beyond the visible range in `PosterRowImagePrefetchEffect`. The visible poster grid separately starts Coil requests when those same items enter composition. Both use the same image URL as the network fetcher's default disk-cache key; the inspected requests do not add request-specific HTTP authentication headers or a different disk-cache key. If a visible item is requested while its prefetch is still in flight, Coil's default `UNCOORDINATED` strategy can run a second network fetch for that same key.

The Android and iOS singleton Coil loaders now opt in to Coil 3.6.3's `DeDupeConcurrentRequestStrategy`. It coordinates by the request's disk-cache key: one fetch runs, concurrent waiters wait for it, then continue through the normal fetch path (which can read the newly written disk-cache entry). The strategy instance is created once by each `KtorNetworkFetcherFactory` and shared by that fetcher factory's requests. Existing memory/disk cache policies, Cache-Control strategy, URL/memory keys, request ordering, prefetch distance, crossfades, custom-poster fallback, and the separate badge loader are unchanged.

Evidence: exact Coil 3.6.3 source for [KtorNetworkFetcherFactory](https://github.com/coil-kt/coil/blob/3.6.3/coil-network-ktor3/src/commonMain/kotlin/coil3/network/ktor3/KtorNetworkFetcher.kt), [NetworkFetcher](https://github.com/coil-kt/coil/blob/3.6.3/coil-network-core/src/commonMain/kotlin/coil3/network/NetworkFetcher.kt) and [DeDupeConcurrentRequestStrategy](https://github.com/coil-kt/coil/blob/3.6.3/coil-network-core/src/commonMain/kotlin/coil3/network/ConcurrentRequestStrategy.kt); Coil's merged [#3326](https://github.com/coil-kt/coil/pull/3326) introduced the strategy and tests for success, failure, cancellation and key independence. Coil 3.6.2 fixed a hanging waiter after cancellation (recorded in the 3.6.3 changelog), so the repository's 3.6.3 pin includes that correction. The strategy remains experimental and disabled by default; the app deliberately opts in only at its two singleton Ktor image-loader factories. No new dependency was added.

Trade-off: requests with disk caching disabled or a failed/unwritable cache may still repeat the network operation after the leader finishes; this strategy coordinates rather than sharing decoded image results. The default app poster loaders have disk cache enabled, which is the intended path. It can also make same-key waiters wait behind a failed leader before retrying; Coil's own tests cover that recovery path. Runtime request counts and load latency have not been measured on devices in this cycle.

