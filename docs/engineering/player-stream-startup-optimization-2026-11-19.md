# Player stream startup optimization — 19 November 2026

## Finding

The Android ExoPlayer HLS VOD path had an avoidable serial preflight. Before the player surface could be created, the Compose runtime performed its own HTTP GET for an HLS master playlist, parsed the variants, generated a sanitized single-variant playlist for each quality and only then passed a URL to the native player. Media3 then prepared the stream from that derived URL. This put application-owned quality discovery ahead of the first player request and made the master-playlist request part of the player-entry critical path.

The Android Media3 HLS module accepts a multivariant/master playlist directly. Media3's HLS implementation chooses and adapts among `#EXT-X-STREAM-INF` variants using bandwidth and device capability. Its HLS factory enables chunkless preparation by default. That makes the separate preflight unnecessary for ordinary Auto playback on the native ExoPlayer route.

## Changes integrated

### Cycle 61 — start native HLS immediately

- For Android playback routed to ExoPlayer, the original HLS master URL is now handed to the player immediately rather than setting `activePlaybackSourceUrl` to null while the app fetches and rewrites the master playlist.
- Variant inspection was moved out of the pre-prepare path. It runs only after the player reaches the existing initial-ready signal.
- iOS and libmpv continue to use the existing app-side quality resolver and its sanitized per-variant playlists.
- Non-HLS sources, P2P/torrent sources, HLS with a separate audio URL and Live TV keep their existing routing boundaries.
- The existing HLS parameter-set compatibility DataSource, stream request/response headers, subtitle handling, Media3 load control and post-error MIME probing were not changed.

### Cycle 62 — make Auto genuinely adaptive

- Native Android ExoPlayer Auto now retains the original multivariant master playlist. This allows Media3's native adaptive track selection to respond to bandwidth and decoder capability instead of locking Auto to the app's highest recommended single variant.
- Explicit quality choices continue to use the existing sanitized single-variant playlists. The user's manual 720p/1080p-style choice therefore remains a deliberate fixed selection.
- The quality UI reports Auto plus the actual decoded resolution and describes Auto as adaptive. The playback-info overlay no longer presents the resolver's recommended variant as though it were the selected fixed quality.
- The existing iOS/libmpv Auto recommendation behaviour is preserved.

### Cycle 63 — make quality discovery on-demand

- Native HLS variant inspection now runs only if the user opens the quality panel after the player is ready. Normal playback does not perform a second app-owned master-playlist GET or create temporary variant playlists.
- The resolved variant list is retained for the source and reused on subsequent panel openings. Closing the panel before resolving cancels the Compose effect; reopening can request the list again.
- If the panel is opened before playback reaches ready, the existing loading state remains visible and inspection waits until the player is ready.

## Regression coverage added (not executed)

Added `composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/PlayerQualitySelectionStateTest.kt` with contracts covering:

- Adaptive Auto maps to the original multivariant URL (both null and explicit Auto ID).
- A manual quality ID still maps to the sanitized single-variant playlist.
- Legacy/non-native Auto still maps to the recommended compatible variant.
- The quality resolver waits for a visible panel and a ready native player.
- An already-resolved source does not trigger another inspection.
- Adaptive Auto is not displayed as a locked recommended variant; a manual selection remains visible.

The test source was reviewed and the synthetic state mappings were checked in memory. The common test suite, Gradle compilation, APK generation and device playback were not run.

## Evidence and expected effect

- The direct-master and automatic-adaptation contract is documented in [Android Developers — HLS playback](https://developer.android.com/media/media3/exoplayer/hls).
- The post-prepare track availability and adaptive-group behaviour are documented in [Android Developers — Track selection](https://developer.android.com/media/media3/exoplayer/track-selection).
- The repository pins Media3 1.8.0; the [1.8.0 HlsMediaSource implementation](https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer_hls/src/main/java/androidx/media3/exoplayer/hls/HlsMediaSource.java) was checked directly. Its factory defaults `allowChunklessPreparation` to true.
- The AndroidX performance explanation [HLS playback in ExoPlayer](https://medium.com/google-exoplayer/hls-playback-in-exoplayer-a33959a47be7) notes that complete multivariant metadata supports efficient first selection and can avoid wasted segment downloads.
- The primary expected gain is removing a serial, app-owned master-playlist GET, parse and temporary-playlist generation before ExoPlayer begins preparation. This is a reduction in critical-path work, not a measured millisecond claim.
- The existing `PLAYER_DIAGNOSTIC_TAG` `prepareStartedAtMs` and `onRenderedFirstFrame` elapsed-time logs remain available for later real-device before/after comparison.

## Compatibility boundaries and verification

- Only Android ExoPlayer playback for HLS sources without separate audio is switched to native adaptive master-playlist playback.
- Manual quality selection continues through the existing variant-specific playlist route. The quality panel's metadata fetch happens after ready and only on demand.
- iOS, libmpv, P2P, non-HLS, source headers, subtitles, HLS SPS/PPS compatibility and buffer/load-control behaviour were deliberately left unchanged.
- Changes were integrated into `main-build-testing` as four fast-forward commits after the starting checkpoint, each using an expected-head branch lease and followed by a remote branch refetch.
- Static source checks and in-memory fixture/state checks were performed. The actual common tests, shell tests, Kotlin compilation, Gradle tasks, APK/AAB builds, Macrobenchmark, CI, LADB and physical-device playback were not run. No real TTID/first-frame improvement or quality regression is claimed.

## Android custom DNS startup follow-up (Cycle 67)

- The Android custom DoH resolver used to issue A then AAAA sequentially. It now queues AAAA asynchronously before performing the required A lookup, overlapping the two independent requests. System-DNS users are unchanged.
- A failure remains authoritative and cancels AAAA; an AAAA failure remains optional. Response closure, existing TTL/cache expiry logic and IPv4-first ordering are preserved.
- Added three host-test contracts for request ordering, optional AAAA failure and cancellation on A failure. Test source reviewed, not executed.
- Expected effect is lower custom-DoH lookup critical-path latency when A and AAAA responses have meaningful independent delay. No measured stream-startup gain is claimed.
- Details and the separate dependency audit: [Android DoH parallelization](player-doh-parallel-dns-2026-11-19.md). No Gradle tasks, tests, builds or CI were run.


## Follow-up under review — Smart Streams filters and first-load diagnostics (21 November 2026)

This follow-up is isolated on `review/playback-startup-best-match-20261121`; it is not merged into `main-build-testing`.

### Smart Streams Best Match consistency

- The source-wide Play Best recommendation now applies the same Smart Streams filters to `allState.groups` and the selected-provider filter through `allState.filteredGroups`.
- The visible stream list still applies Smart Streams filters to `listState.groups` after the existing free-text search. Play Best intentionally remains independent of the lower-page free-text search, preserving its existing placement and source-wide role.
- Existing stream scores, weights, low-quality fallback, and stable input-order tie-break are unchanged.
- Added `SmartStreamRankingTest` cases for resolution filtering, Instant Only excluding an unresolved addon torrent, provider selection, and independence from free-text list search.

### Android Media3 initial load diagnostics

- Added Media3 1.8.0 `AnalyticsListener.onLoadStarted` and `onLoadCompleted` handling for the first manifest load and first media load per listener/player instance.
- Start events exclude retries; completion events are capped to one manifest and one media event, avoiding per-segment log flooding.
- Logs include elapsed realtime from the existing prepare start, load duration, byte count and track type. Start URLs pass through `InAppLogger.redactUrl`; no headers or raw query parameters are added.
- Negative elapsed events are discarded before first-event flags are consumed. This rejects only callbacks whose event timestamp predates the current prepare; it does **not** prove that a callback arriving later belongs to that prepare.
- This supplements existing decoder initialization, load-error and first-frame diagnostics. It does not change player selection, HLS requests, buffer thresholds, retry handling, DNS, or link cache policy.
- **Important limitation found during review:** `EventTime` carries both event-specific and current timeline/window/media-period identity. The current implementation does not compare those fields. ExoPlayer can emit events for pre-buffered or previous items, so `attempt` is the current counter at callback time, not a proven owner of the event. The one-time flags are per listener lifetime, not per prepare attempt. Do not use these new logs as authoritative per-attempt startup measurements until event correlation and guard reset are implemented and verified.

### Verification boundary

- The implementation, regression-test source and documentation were re-fetched from the review branch for static inspection.
- Tests have not been run; the test-first RED result is not established. Kotlin compilation, Android build, device playback, Macrobenchmark and CI have not been run.
- No measured startup or buffering improvement is claimed. The diagnostic data must be collected on a physical device and compared across representative streams/networks before considering any further playback-policy changes. Before doing so, add and verify event-to-current-media identity checks using Media3 `EventTime.timeline/windowIndex/mediaPeriodId` and the `current*` counterparts, and make first-load guards correspond to a real prepare attempt.
- The review branch is ahead of `main-build-testing` with no merge-base divergence. The target branch remains untouched.

### HLS inspection timing instrumentation (21 November 2026)

- Added elapsed-realtime instrumentation around the existing HLS playlist SPS/PPS compatibility inspection.
- Each eligible initial playlist inspection now logs elapsed milliseconds, playlist byte count, whether the playlist was rewritten, and whether the measured duration reached the 3-second inspection timeout.
- The log deliberately excludes playlist and segment URLs, query strings, request headers, and stream identifiers.
- This is measurement-only: playlist parsing, segment-head probing, timeout, rewrite eligibility, and fallback behavior are unchanged. It does not claim to improve playback startup.
- Use these measurements on healthy and affected HLS VOD sources to determine whether inspection is a meaningful first-frame critical-path cost before changing the compatibility strategy. Compare with first-frame elapsed time and network load events on the same device and connection.
- No tests, Kotlin compilation, Android build, CI, or physical-device measurements have been run for this instrumentation.


### Media3 preload feasibility audit (21 November 2026)

The proposed idea of downloading the first few minutes after a stream is selected and then continuing in the background was checked against the actual Android player construction path and Media3's preload-manager contract.

- Media3 playback is already incremental. The player buffers ahead while consuming the current stream; downloading a fixed multi-minute prefix is not a general prerequisite for starting playback and could increase startup delay, memory use, network contention, and wasted transfer when the user seeks or leaves.
- `DefaultPreloadManager` is designed for a known, ordered set of likely-next media items (for example, a feed or carousel). The current player is constructed around a selected source and a per-source Compose lifecycle, not an already-registered queue of candidate media items.
- The current ExoPlayer builder uses app-specific `DefaultTrackSelector`, `LoadControl` (including live-specific wrapping), renderer customization, and a custom `DataSource.Factory`. That factory can include request/response headers, the HLS SPS/PPS compatibility wrapper, optional progressive-source buffering/cache, byte counting, and special handling for YouTube chunked, loopback, live, and external subtitle paths.
- The current player may also build merged video/audio sources, Blu-ray sources, and libass-enabled players. A preload manager must create the playback player through the same builder and share compatible components; a preloaded `MediaSource` must be handed to that linked player on the shared playback looper. Bypassing those paths risks losing headers, custom HLS handling, subtitles, track-selection consistency, or decoder/render configuration.
- The AndroidX Media3 guidance explicitly requires the same `DefaultPreloadManager.Builder` to build the preload manager and player, and calls out shared LoadControl, TrackSelector implementation, MediaSourceFactory, RenderersFactory and playback looper. It also warns that unmanaged preload queues retain sample queues and can exhaust memory unless a bounded sliding window removes distant items.
- The repository is pinned to Media3 1.8.0. Context7 exposed 1.8.1 as its nearest indexed version; the official AndroidX 1.8.0 release notes and official Android Developers preload guidance were used as cross-checks. No 1.8.1-only API has been assumed.

**Decision:** Do not add speculative multi-minute preloading to the current player. First establish a real pre-selection opportunity in the stream chooser (a stable, bounded list of likely next sources) and prove that its source metadata, headers, selected quality, and player lifecycle can be handed off without bypassing current custom factories. Only then prototype a small configurable preload target and compare first-frame latency, network bytes, memory, and cancellation/waste against the existing path.

### Analytics event ownership — further review (21 November 2026)

- Re-inspected the current Android analytics listener and the prepare call site. The listener's first-load flags live for the listener lifetime, while `prepareStartedAtMs` and `attempt` are updated by the current prepare path. A callback from a previously active or pre-buffered media item can therefore consume a first-load flag or be labelled with the newer attempt.
- Media3's official `AnalyticsListener.EventTime` documentation distinguishes the event's `timeline/windowIndex/mediaPeriodId` from `currentTimeline/currentWindowIndex/currentMediaPeriodId`. The current item can differ from the item associated with the event.
- A timestamp check (`eventTime.realtimeMs >= prepareStartedAtMs`) cannot prove ownership. A robust guard must identify the event's media item/period against the intended source and explicitly handle events emitted before the timeline is populated; blindly rejecting all empty-timeline events would also lose legitimate initial manifest observations.
- **Decision:** retain the existing logs as provisional diagnostics only. Do not use them as authoritative per-attempt manifest/media timing, and do not tune buffer policy or claim a measured speedup from them until event ownership and prepare-attempt boundaries have a testable implementation.

No production playback behavior was changed in this audit. No tests, Kotlin compilation, Android build, CI, Macrobenchmark, or physical-device playback were run.


### Android Baseline Profile packaging audit (21 November 2026)

- The generic `androidApp/src/main/baseline-prof.txt` is zero bytes, but the app config explicitly sets `baselineProfile.mergeIntoMain = false`; do not treat the generic file alone as proof that a release flavor has no generated profile.
- The profile generator module is wired to `:androidApp` with matching `full` and `playstore` flavors. Profile saving is enabled, but automatic generation during builds is disabled.
- The correct next verification is to inspect the generator's actual startup and in-app journeys, generate each release-flavor profile through the explicit profile task on a supported device, and confirm the compiled profile is packaged in each release artifact. Do not hand-author rules or infer a speedup from source configuration.
- Generator source path and flavor-specific generated profile contents could not be verified through the available repository file endpoint. No profile-generation task, build, CI, device benchmark or playback test was run.
- Detailed findings and acceptance criteria are recorded in [codebase improvement notes](codebase-improvement-notes.md), Cycle 128.


### Android Activity-to-first-frame audit (21 November 2026)

- `MainActivity.onCreate()` performs five named synchronous initialization groups on the main thread before `setContent`: pre-super, identity/network, core storage, profile metadata, and integrations/downloads. Each group is already wrapped in a `Trace.beginSection/endSection` marker.
- `SentryInitializer.start()` is called inside the pre-super group. Its initial settings load and `applyEnabled` execute synchronously on the caller; when enabled with a configured DSN, this path invokes `SentryAndroid.init` before `super.onCreate()`.
- The separate critical preload decodes the profile payload on IO and runs watched/watch-progress plus Home snapshot preload through IO. Post-frame warmup is deferred until after the first Compose frame.
- No initializer was moved or deferred: the current trace slices should first be measured on a physical device. Compare the five slices and first Compose frame with Sentry enabled and disabled before deciding whether any initialization is actually on the critical path.
- No Perfetto capture, tests, Gradle tasks, build, CI or device benchmark was run. This is a source-level call-path finding, not evidence of a measured delay.
- Detailed call-path notes are in [codebase improvement notes](codebase-improvement-notes.md), Cycle 129.


### Theme settings first-composition storage follow-up (Cycle 130, 21 November 2026)

A new source path was inspected after the Activity-to-first-frame audit: `MainActivity.showContent()` enters Compose, then root `AppEnvironment` calls `ThemeSettingsRepository.ensureLoaded()` synchronously inside `remember`. On first load, this reads multiple SharedPreferences values, decodes persisted theme/Liquid Glass state, performs two custom-background-image file existence/length checks, and applies theme/native-tab state before composition continues. The Android storage initialization itself is already in the pre-super Activity trace, but this repository hydration occurs later during initial Compose composition.

This is a concrete candidate for first-composition work, not proof of a measurable bottleneck. Do not blindly defer it: selected theme, AMOLED state, Liquid Glass surface overrides, profile-scoped values, locale application and legacy migration all affect initial UI state. The next evidence step is a dedicated trace around `ensureLoaded()` with cold and warm launches, then a test-backed readiness design if it materially contributes to TTID/TTFD. DataStore is asynchronous by design, but replacing this existing storage contract would require a deliberate migration and parity plan.

Coil 3.6.3 was also checked: the Android `SingletonImageLoader.Factory` and root-composable `setSingletonImageLoaderFactory` used on the platform paths match Coil's documented configuration options. No loader change was justified. No code, tests, builds, CI, Perfetto or device measurements were performed.


### Dual-stack DNS resolution follow-up (Cycle 132, 21 November 2026)

The custom Android DoH resolver starts AAAA asynchronously, performs A synchronously, then waits without a separate resolution-delay bound for the AAAA callback. Its DoH client has an 8-second call timeout, so an available IPv4 answer can be held while a slow AAAA request completes or times out. RFC 8305 §3 advises clients not to wait for both address families before connection attempts and recommends a 50ms resolution delay after an A response. This is a concrete latency candidate, but the current OkHttp `Dns.lookup` API returns a completed list; a safe change needs to preserve IPv6 preference where available, retain late AAAA answers for future lookups, and cover provider changes/cache expiry/cancellation. No arbitrary cutoff was added. See Cycle 132 in the codebase notes for the full evidence and next test matrix.

### Startup preloader call-path clarification (Cycle 133, 21 November 2026)

The critical preloader calls `ProfileRepository.loadCachedProfiles()` from its Main scope, but profile payload decoding and PIN-cache synchronization explicitly switch to `Dispatchers.IO`. After applying the profile, it calls `ThemeSettingsRepository.onProfileChanged()` on the caller dispatcher, which enters the synchronous theme settings loader already identified in Cycle 130. This is the same shared settings path, not a second independent cache-read issue. No code or tests changed; the existing helper tests were inspected but not run.


### Home snapshot preload dispatcher — correction (Cycle 134, 21 November 2026)

The preceding proposed main-thread I/O finding was incorrect and is withdrawn. Although `StartupPreloader`'s owning scope uses `Dispatchers.Main`, its critical phase calls `runStartupPreloadWork(dispatcher = Dispatchers.IO)`. The Home snapshot operation is one of that helper's independent reads, launched using `async(dispatcher)`. Consequently the snapshot file read, JSON decode and model mapping execute on IO, not Main. No additional dispatcher switch is warranted, and no production change should be made for this path.

The existing `StartupPreloaderTest` verifies overlap between independent startup reads and ordered critical reads; it does not directly assert thread affinity for Home snapshot loading. Source tracing is sufficient to reject the proposed duplicate dispatcher change. No production code or tests changed, and no tests, build, CI, StrictMode, Perfetto or device benchmark was run.


### HLS SPS/PPS inspection latency and Media3 source attribution follow-up (21 November 2026)

Two additional source-level findings were verified against the review branch:

1. `HlsParameterSetInitDataSource.open()` reads the playlist, then synchronously waits up to `CHECK_TIMEOUT_MS = 3_000` for the optional segment-head inspection. A slow second-segment host can add up to three seconds before an otherwise healthy playlist is returned unchanged. The timeout does not include the preceding playlist open/read. Cycle 142 corrected the `timedOut` diagnostic to use the actual timeout callback rather than infer it from elapsed milliseconds. The existing timeout test still exercises an interruptible worker, not a network DataSource's interruption and close behavior; a DataSource that ignores interruption can leave the daemon worker running until its I/O returns. Do not reduce the timeout without proving the SPS/PPS repair still works on affected VOD; design an injectable DataSource test and measure the critical-path delay first. See Cycles 136 and 142 in the codebase notes.

2. For separate audio URLs, `PlayerEngine.android.kt` merges distinct video and audio MediaSources. Media3 1.5.0+ reports load events from secondary merged sources too; the current first-media diagnostic can therefore be audio rather than video. The exact Media3 1.8.0 source/API was inspected. Keep this metric provisional and separate video/audio/subtitle roles before using it for first-frame analysis. See Cycle 137 in the codebase notes.

Cycle 138 found that only the initial ExoPlayer start path began a fresh `PlaybackDiagnostics` attempt. Cycle 143 now routes all six ExoPlayer prepare boundaries (initial, Blu-ray, retry and three subtitle reload paths) through one helper that resets the per-attempt guards. Delayed callbacks still require event-specific identity correlation before these logs can be treated as authoritative. See Cycles 138 and 143 in the codebase notes.

No production code or tests changed in these audits. No test task, build, CI, network fault injection, benchmark or device run was started.


### VOD cache-size setting lifetime (21 November 2026)

Cycle 139 confirmed that the process-wide PlaybackDiskCache retained the initial LeastRecentlyUsedCacheEvictor limit after settings changed. Cycle 140 adds a runtime-adjustable LRU CacheEvictor on the same SimpleCache and observes the two policy fields through the settings StateFlow. Cycle 141 corrects an important reuse-path issue: PlayerEngine passes a remembered settings snapshot, so an existing cache must not reapply that potentially stale value or perform synchronous eviction on the playback setup caller. The live IO collector is now the sole authority for policy changes after cache creation. Host test source covers limit decreases/increases, span-touch LRU ordering and size calculation, but remains unexecuted. See Cycles 140–141 in the codebase improvement notes for the concurrency and stale-snapshot rationale. This fixes a settings correctness mismatch and removes synchronous eviction from the playback setup path; it is not a measured playback speedup.


## Android HLS repair eligibility and bounded segment inspection (Cycles 151–152, 22 November 2026)

### Cycle 151 — require an IDR first slice before injecting SPS/PPS

- The segment scanner previously treated every H.264 coded-slice NAL type (1–5) as equivalent. It therefore considered a segment beginning with a P/B slice to be a candidate for SPS/PPS repair.
- SPS/PPS restore codec configuration, not the reference pictures required by a P/B slice. After a seek or fresh extractor, such a segment is not independently decodable merely because the parameter sets were injected.
- The scanner now records whether the first slice is NAL type 5 (IDR). Playlist rewriting is allowed only when the second segment has reached an IDR first slice, lacks SPS/PPS, and the first segment supplies matching parameter sets.
- Added host regression coverage for non-IDR first slices and verified the scanner's classification in the test source. Existing IDR fixtures retain the repair path.

### Cycle 152 — bound the underlying HTTP inspection call

- The previous `FutureTask.get(3s)` deadline interrupted the worker, but the player network client used a 15-second read timeout and interruption alone does not reliably abort a blocked OkHttp response-body read. A timed-out inspection could therefore leave a daemon worker and network request alive well past the advertised budget.
- Added a dedicated segment-inspection DataSource factory using a derived OkHttp client with a 2.5-second whole-call timeout. OkHttp's `newBuilder()` retains the existing dispatcher, connection pool, DNS, TLS configuration and interceptors; the existing source request headers and Authorization injection are retained. The outer 3-second FutureTask deadline remains a final guard.
- The playlist's normal playback DataSource retains its existing timeouts. Only the optional segment-head probe uses the shorter whole-call deadline. The full flavor's YouTube chunked transport keeps its existing fallback instead of being bypassed by a different HTTP stack.
- Added a host test proving the HLS wrapper creates segment probes from the dedicated inspection factory.

**Evidence and verification boundary**
- The repository pins Media3 1.8.0; its `DataSource` and `OkHttpDataSource.Factory` contracts were reviewed. Media3 1.8.0's OkHttp datasource dependency resolves to OkHttp 4.12.0. OkHttp 4.x documentation/source confirms `callTimeout(long, TimeUnit)` spans DNS, connection, retries/redirects and response-body reads, and `newBuilder()` inherits the existing client configuration and shared connection resources.
- Context7 documentation for AndroidX Media3 and OkHttp was consulted; Context7 did not expose an exact Media3 1.8.0 or OkHttp 4.12.0 versioned library entry, so the pinned dependency and upstream source/POM evidence were used for version-specific checks.
- Source and host regression fixtures were updated on the feature branch. Tests and Kotlin compilation have not been run. No Gradle task, build, CI, network fault injection, device playback or benchmark was started. No measured playback speedup is claimed.


## Progressive audio selection and DataSource factory invalidation (Cycles 153–154, 22 November 2026)

### Cycle 153 — classify each progressive resource independently

- The buffering URL selection previously passed the video's response headers and stream type to every URL, including a separately supplied audio URL. A progressive MP4 audio resource paired with an HLS video could be incorrectly excluded from VOD cache/native read-ahead; an adaptive audio URL paired with a progressive video could be incorrectly selected for progressive buffering.
- Added `playbackBufferedUrls`: video classification uses its own response headers and stream type, while separate audio classification uses its own URL/MIME hints only. Blank or absent audio URLs are ignored, and opaque audio URLs without a recognizable MIME hint are excluded rather than assumed progressive. Existing exclusions for live streams, YouTube chunked playback and loopback sources remain unchanged.
- Added host regression cases for progressive audio with HLS video, adaptive audio with progressive video, isolation from video response headers, and blank audio URLs.

### Cycle 154 — invalidate factories when captured dependencies change

- The remembered DataSource factory captured `sourceAudioUrl`, `normalizedStreamType` and `networkBytesRead`, but its remember keys did not include those values. `playerSourceKey` already contains the source URL, separate audio URL, normalized stream type, sanitized request/response headers, YouTube mode and position-request key; the byte counter is separately remembered from that source key.
- The factory now keys on `playerSourceKey`, the byte-counter instance, subtitles, live-stream state and context. This makes the factory and byte-counting wrapper refresh with the same source/diagnostic lifetime instead of retaining an old audio classification or incrementing an obsolete counter.

**Evidence and verification boundary**
- The exact source and remember capture list were inspected on `main-build-testing`. Compose's documented `remember` contract compares all keys with `equals` and recalculates when any key changes; Context7 documentation and Android Developers guidance were checked. The repository pins Compose Multiplatform 1.13.0-beta01; the Context7 Android reference is current but does not expose that exact multiplatform prerelease as a versioned entry.
- Four host regression tests were added for independent audio/video classification. The remember-key correction is a direct dependency-completeness fix; it reuses the existing `playerSourceKey` source identity and includes the newly captured byte-counter identity.
- Host tests and Kotlin compilation were not run. No Gradle task, build, CI, device playback or benchmark was started. No numerical startup or playback improvement is claimed.
