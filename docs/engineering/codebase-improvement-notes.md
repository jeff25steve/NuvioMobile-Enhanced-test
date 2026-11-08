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
