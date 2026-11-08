# Nuvio Mobile Enhanced — Engineering Improvement Notes

**Repository:** `jeff25steve/NuvioMobile-Enhanced-test`  
**Working branch:** `main-build-testing`  
**Review started from:** `e86df525fa0c633adc753467220592d45bb0a1f9`  
**This continuation rechecked:** `a9f6d930cb6ee436046bf54b9e1e4533e993cf62`  
**This continuation (8 November) rechecked:** `943bdb1c71ad5f4f523f9dd1b61138a608cbeeec`  
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
