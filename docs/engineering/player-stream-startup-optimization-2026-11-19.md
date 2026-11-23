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
- Historical version note: this preload audit was written when the repository used Media3 1.8.0. The current gradle/libs.versions.toml on main-build-testing pins Media3 1.11.1 (verified 22 November 2026); do not reuse the old version statement for current API decisions. Re-check the exact pinned version before implementing any preload integration.

**Decision:** Do not add speculative multi-minute preloading to the current player. First establish a real pre-selection opportunity in the stream chooser (a stable, bounded list of likely next sources) and prove that its source metadata, headers, selected quality, and player lifecycle can be handed off without bypassing current custom factories. Only then prototype a small configurable preload target and compare first-frame latency, network bytes, memory, and cancellation/waste against the existing path.

### Analytics event ownership — further review (21 November 2026)

- Re-inspected the current Android analytics listener and prepare call sites. `PlaybackDiagnostics.beginAttempt()` resets the first-load and completion guards, and all six prepare call sites invoke `beginExoPlayerPrepareAttempt()` (verified in `PlayerEngine.android.kt` on 22 November 2026). The earlier statement that those guards lived for the listener lifetime was incorrect. A callback from a previously active or pre-buffered media item can still consume a freshly reset guard or be labelled with the newer attempt.
- Media3's official `AnalyticsListener.EventTime` documentation distinguishes the event's `timeline/windowIndex/mediaPeriodId` from `currentTimeline/currentWindowIndex/currentMediaPeriodId`. The current item can differ from the item associated with the event.
- A timestamp check (`eventTime.realtimeMs >= prepareStartedAtMs`) cannot prove ownership. A robust guard must identify the event's media item/period against the intended source and explicitly handle events emitted before the timeline is populated; blindly rejecting all empty-timeline events would also lose legitimate initial manifest observations.
- **Decision:** retain the existing logs as provisional diagnostics only. Prepare-attempt guard resets are now present at all six prepare call sites, but event-to-media ownership is still unresolved. Do not use these logs as authoritative per-attempt manifest/media timing, and do not tune buffer policy or claim a measured speedup until event ownership has a testable implementation.

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

- The previous `FutureTask.get(3s)` deadline interrupted the worker, but interruption alone does not reliably abort a blocked OkHttp response-body read. The standard HLS inspection path now uses a dedicated OkHttp client with a 2.5-second whole-call timeout, which covers response-body reads as well as DNS, connection and redirects. The outer 3-second `FutureTask` deadline remains a final guard. The full-flavor YouTube chunked path deliberately retains its own transport and falls back to its existing factory; do not assume the OkHttp whole-call deadline applies to that special path.
- Added a dedicated segment-inspection DataSource factory using a derived OkHttp client with a 2.5-second whole-call timeout. OkHttp's `newBuilder()` retains the existing dispatcher, connection pool, DNS, TLS configuration and interceptors; the existing source request headers and Authorization injection are retained. The outer 3-second FutureTask deadline remains a final guard.
- The playlist's normal playback DataSource retains its existing timeouts. Only the optional segment-head probe uses the shorter whole-call deadline. The full flavor's YouTube chunked transport keeps its existing fallback instead of being bypassed by a different HTTP stack.
- Added a host test proving the HLS wrapper creates segment probes from the dedicated inspection factory.

**Evidence and verification boundary**
- Historical version note: this cycle's source audit targeted Media3 1.8.0 and its then-resolved OkHttp dependency. The current branch pins Media3 1.11.1. The current 1.11.1 `OkHttpDataSource` source still implements `read()` through the response-body stream and uses the whole-call `Call` timeout configured on the client; `newBuilder()` preserves the shared client configuration. Re-check the dependency graph before making future version-specific claims.
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


## HLS playlist rewrite memory bound (Cycles 155–156, 22 November 2026)

- The optional SPS/PPS playlist-repair wrapper previously buffered every eligible HLS media playlist in a ByteArrayOutputStream before its segment-inspection timeout began. That left manifest download size outside the existing inspection deadline and without a memory ceiling.
- Cycle 155 adds a 256 KiB rewrite ceiling and immediately passes through a playlist whose DataSource.open() has already resolved a larger length. It does not consume any body bytes during open() on that path.
- Cycle 156 applies the same ceiling when the response length is unresolved: it reads at most 256 KiB plus one sentinel byte. If the sentinel proves the playlist is oversized, the wrapper replays the buffered prefix and then continues reading directly from the still-open upstream DataSource. No manifest bytes are dropped, duplicated, or rewritten on this fallback path.
- Small manifests retain the existing SPS/PPS inspection and rewrite behavior. The limit applies only to this optional playlist-repair buffer; it does not cap normal HLS playback or segment reads.

**Evidence and verification boundary**
- Repository dependency catalog pins Media3 1.8.0. Context7 resolved AndroidX Media3 but exposed 1.8.1 rather than the pinned 1.8.0; the exact 1.8.0 DataSource.java source was therefore checked through the AndroidX Media repository. It confirms that unbounded open() may return C.LENGTH_UNSET when length remains unresolved and that reads continue until RESULT_END_OF_INPUT.
- Exa also checked the Android Developers DataSource API and HLS guidance. The fallback preserves the DataSource contract by replaying the exact prefix before delegating subsequent reads to upstream.
- Added host regression coverage for both known-length bypass and unknown-length prefix replay/continuation. The tests were not executed in this environment. No Gradle task, Kotlin compilation, APK build, CI, network fault injection, device playback or benchmark was run. No playback-speed improvement is claimed; this bounds optional manifest buffering and preserves playback bytes.


## Media3 1.11.1 playback and local-buffer policy (Cycles 157–158, 22 November 2026)

- Cycle 157 upgrades the single androidx-media3 version-catalog entry from 1.8.0 to 1.11.1. All Media3 artifacts in the catalog share that version reference, so HLS, DASH, SmoothStreaming, RTSP, common, extractor, decoder, datasource, session and UI dependencies remain aligned.
- The upstream 1.9.0 release adds a maximum to automatic DefaultLoadControl memory calculation for excessive track selections and corrects custom sample MIME type track classification. It also enables improved seeking for eligible videos and a 15 ms late-decoder-input drop threshold for more efficient frame dropping. Media3 1.11.0 further reduces OOM risk when time thresholds are prioritized and heap is tight. Media3 1.11.1 includes fixes for Matroska seekability when Tracks occur after Clusters, HLS EOF retry behavior, and stale video frames during eligible scrubbing.
- The app's LiveStartLoadControl wrapper was updated for the player-scoped Media3 1.11.1 LoadControl contract: allocator lookup and preload decisions now forward the supplied PlayerId to the delegate. Host tests cover both delegations.
- Cycle 158 separates local and streaming buffer duration policies using the corresponding Media3 1.11.1 builder methods. Custom and native-memory buffer settings explicitly configure both policies; the standard network branch configures streaming thresholds; the existing short local-file policy now uses local-only APIs.
- Media3 classifies content: URIs as local playback. The app previously excluded authorities containing downloads from its local-file policy, causing those URIs to receive the network-style buffer policy despite Media3's own local classification. The local-source check now treats both file: and content: as local; host tests cover file, DownloadsProvider content and HTTP URIs.

**Evidence and verification boundary**
- Context7 resolved AndroidX Media3 1.11.1 and was queried for the LoadControl and builder APIs. Exa checked the official AndroidX 1.11.1 release notes and the exact tagged source for LoadControl, DefaultLoadControl, DefaultRenderersFactory, DefaultAudioSink and MediaCodecVideoRenderer.
- The exact 1.11.1 LoadControl source requires getAllocator(PlayerId) and uses player-scoped shouldContinuePreloading(PlayerId, Timeline, MediaPeriodId, long); the live wrapper now implements and forwards both.
- Tests were added but not executed. No Gradle task, Kotlin compilation, APK build, CI, device playback or benchmark was run. Therefore the dependency/API upgrade is statically inspected but not yet compile-verified, and no numerical startup, seek or frame-drop improvement is claimed.


## Video software decoder priority (Cycle 159, 22 November 2026)

The app's `SubtitleOffsetRenderersFactory` now inserts its custom `SoftwareVideoRenderer` according to Media3's extension mode rather than always appending it. `EXTENSION_RENDERER_MODE_PREFER` inserts it before the existing video renderers; `ON` appends it; `OFF` omits it. This aligns the custom renderer with the same priority contract used by Media3's bundled VP9/AV1/FFmpeg video extensions. The existing capability checks remain unchanged, so the software decoder is only selected for formats it reports as handled.

Context7 and the exact AndroidX Media3 1.11.1 tagged `DefaultRenderersFactory` source confirm the ordering contract. Host tests cover PREFER/ON/OFF insertion behavior but remain unexecuted. No build, CI, device playback or benchmark was run; no measured playback improvement is claimed.


## FFmpeg compressed-sample padding safety (Cycles 160–161, 22 November 2026)

The custom software video decoder now allocates each direct Media3 input buffer with FFmpeg's required 64-byte packet tail and zeroes that tail before calling `avcodec_send_packet`. This avoids a full packet copy while meeting FFmpeg 8.0's bitstream overread contract. The JNI boundary independently checks that the supplied direct buffer has at least `sampleSize + AV_INPUT_BUFFER_PADDING_SIZE` bytes of capacity and rejects invalid input before passing it to libavcodec. Host tests cover padding capacity, zeroing, and sample preservation.

The repository's vendored FFmpeg headers identify libavcodec major 62 (FFmpeg 8.0). The versioned FFmpeg 8.0 documentation warns that missing padding can lead to overreads and segfaults; the Media3 1.11.1 `DecoderInputBuffer` source shows the default padding is zero unless explicitly requested. Tests and both native/Kotlin compilation remain unexecuted. No build, CI, device playback or benchmark was run.


## Per-frame video colour metadata (Cycle 162, 22 November 2026)

The software video decoder now carries Android dataspace metadata with each `VideoDecoderOutputBuffer` instead of keeping one mutable dataspace on the decoder instance. This prevents decode-ahead from allowing a newer frame's colour primaries, transfer function or range to be applied to an older queued frame. The JNI render call now receives the dataspace associated with the exact output buffer being rendered. A host regression test covers independent metadata retention across queued frames.

The exact Media3 1.11.1 source documents `VideoDecoderOutputBuffer.decoderPrivate` as per-buffer decoder-private storage. Test, Kotlin/native compilation and HDR device validation remain unexecuted. No build, CI or playback benchmark was run.


## Native video dataspace error handling (Cycle 163, 22 November 2026)

The native surface renderer now checks the status returned by `ANativeWindow_setBuffersDataSpace`. It no longer records a dataspace as active when Android rejects it; failures are logged once per distinct dataspace attempt on a surface. Replacing the surface clears both applied and attempted state so the new surface gets its own attempt. The Android NDK API documents 0 for success and `-EINVAL` for an invalid window or unsupported dataspace.

No native compilation, build, CI or device validation was run.
### Local downloaded-video MIME inspection fast-path audit (22 November 2026)

- `playbackMediaItemFromUrl()` resolves MIME synchronously. For `file://` and `content://` URLs, `inferMimeTypeFromLocalContent()` can read up to 4 KiB on `Dispatchers.IO` and then block the caller with `runBlocking` when called from the main thread. The Android ExoPlayer setup path constructs its resolved media item during Compose setup, so this can add local storage/provider latency before prepare.
- The current source order is explicit stream type, response headers, local content sniff, then URL path. The content sniff can correct a misleading adaptive extension (for example, a downloaded progressive container named `.m3u8`) before `DefaultMediaSourceFactory` chooses HLS/DASH/SmoothStreaming versus progressive playback.
- Media3 1.11.1 `DefaultMediaSourceFactory` routes explicit adaptive MIME types to their matching adaptive source factories; its fallback is `ProgressiveMediaSource`, which uses `DefaultExtractorsFactory` to sniff the actual progressive container. This makes the 4 KiB local read a candidate for narrowing to cases where a path hint would otherwise select an adaptive source. It is not safe to remove content sniffing wholesale: misleading `.m3u8`, `.mpd` or `.ism` local names can change the selected media-source implementation.
- At the time of this audit, no production change had been made. Cycle 165 below records the follow-up implementation and its remaining verification boundary.
- Version-specific references checked: https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/DefaultMediaSourceFactory.java, https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaSource.java, and the branch's `gradle/libs.versions.toml`.


### Follow-up implementation — local progressive MIME fast path (Cycle 165, 22 November 2026)

The MIME inference path now avoids local payload inspection when the URL already identifies a known progressive container. This removes the synchronous 4 KiB pre-read and main-thread wait from common local MP4/Matroska/WebM/TS/MOV/AVI/MPEG sources. Unknown local paths and adaptive-looking paths still inspect content, preserving the ability to correct a misleading adaptive extension before Media3 selects a source implementation. Explicit stream type and response-header MIME hints remain highest priority.

A host regression test covers the decision policy. It has not been run; no Kotlin compilation, Gradle task, build, CI, device playback or startup benchmark was performed. The source-level behavior change is integrated, but runtime correctness and the magnitude of any startup improvement remain unverified. Content URIs without a recognizable path MIME still take the inspection path.

## Native read-ahead memory and DataSource lifecycle corrections (Cycles 166–167, 22 November 2026)

The native read-ahead queue previously counted queued bytes against the configured capacity but allocated every backing block at the fixed 16 MiB maximum and always requested 32 KiB from upstream. The producer now limits each upstream read to the remaining queue capacity and sizes each newly allocated block to the smaller of 16 MiB or the remaining configured window. Block boundaries use the actual mapped buffer capacity, so smaller custom read-ahead windows no longer require a full 16 MiB first allocation.

The wrapper also now clears its cached URI and response headers when closed. On a failed upstream open it preserves the attempted-open metadata until close, as required by Media3's DataSource contract. This prevents stale resource metadata from surviving a completed close.

The exact Android SharedMemory API documents that `create(name, size)` allocates the requested region and `mapReadWrite()` maps it. Media3 1.11.1 DataSource API/release notes document URI/header lifecycle, including failed opens. Host tests were added for block sizing; they were not run. No Kotlin compilation, Gradle task, build, CI, device playback or memory benchmark was performed. Memory reduction and playback impact remain unmeasured.


## Read-ahead EOF reclamation and close-error handling (Cycles 168–169, 22 November 2026)

The read-ahead consumer now releases a partially filled final native-memory block as soon as all its written bytes have been consumed and the producer has reached EOF or exited. It does not release a partial block while the producer can still append; full blocks remain reusable within the active session.

The DataSource close path no longer suppresses upstream close failures. It still waits for the read-ahead thread, propagates cleanup errors, and attaches an upstream-close error as suppressed if waiting itself fails. Source URI and response-header metadata are cleared regardless of cleanup outcome.

Host regression tests were added for partial-block reclamation and close-failure propagation. They were not run. Context7 checked the exact Media3 1.11.1 DataSource contract; Exa checked the corresponding OkHttp-backed implementation and response-body cleanup behavior. No Gradle task, Kotlin compilation, build, CI, device playback, network fault injection or memory benchmark was run. These changes improve native-memory lifetime and cleanup observability; no playback-speed improvement is claimed.


## Further read-ahead, source-wrapper and MIME-header lifecycle fixes (Cycles 170–173, 23 November 2026)

Four additional source-level corrections were integrated:

- The native read-ahead producer now frees its reusable mapped-block pool when it finishes, and terminal full blocks are freed instead of recycled. This complements the prior partial-final-block reclamation.
- `SelectiveBufferedDataSource` clears its active reference even when delegate close fails and discards only the failed delegate, so a later open can obtain a fresh instance without sacrificing reuse after successful closes.
- Response-header overrides now match HTTP's case-insensitive field-name semantics. Case variants from the upstream response are removed before the override is inserted, avoiding conflicting MIME metadata.
- Failed native block creation now cleans up acquired SharedMemory mappings/descriptors and file-backed fallback resources, preserving the allocation error and retaining cleanup failures as suppressed exceptions.

Host regression tests were added for these cases but were not run. Context7 checked the exact Media3 1.11.1 dependency and DataSource lifecycle; Exa checked the Android SharedMemory API and RFC 9110 §5.1. No Gradle tasks, compilation, builds, CI, device playback, fault injection or performance benchmarks were run. These are resource-lifecycle and source-classification correctness improvements; no measurable playback-speed improvement is claimed.


## Complete shared-memory allocation and release cleanup (Cycle 174, 23 November 2026)

The SharedMemory-backed read-ahead allocator now attempts descriptor close even if unmapping fails, during both allocation rollback and normal block release. If both cleanup operations fail, the descriptor-close exception is propagated with the unmap exception preserved as suppressed. The file-backed fallback also attempts temporary-file deletion when closing its file handle fails.

Host regression coverage was added for the two-failure path and descriptor closure after unmap failure; it was not run. Android's SharedMemory API documentation confirms that `close()` and `unmap()` are distinct operations: closing the descriptor alone does not release existing mappings. No Gradle task, Kotlin compilation, build, CI, device playback or memory benchmark was run.


## Software video decoder latency — slice-first threading (23 November 2026)

The Android FFmpeg software-video fallback previously requested both `FF_THREAD_FRAME` and `FF_THREAD_SLICE` for every codec. That is not a neutral “enable both” setting: FFmpeg's own threading implementation selects frame threading first when both modes are enabled. The bundled FFmpeg 62 headers explicitly document that frame threading adds one frame of decoding delay for every thread beyond the first.

The decoder now chooses its threading mode from the actual `AVCodec.capabilities`:

- Prefer `FF_THREAD_SLICE` when `AV_CODEC_CAP_SLICE_THREADS` is advertised. This parallelizes work within a frame instead of building a queue of future frames.
- Use `FF_THREAD_FRAME` only when the codec lacks slice-thread support but advertises frame-thread support. Cap this fallback at two threads, bounding the documented extra delay to one frame rather than allowing up to seven extra frames on an eight-thread configuration.
- Use one thread when neither multi-threading capability is advertised.
- Clamp the native thread request to the same eight-thread ceiling as the Kotlin renderer, and log the selected codec, actual mode, effective thread count and capability bits for device-side diagnosis.

This is a targeted latency correction to the software-decoder fallback (currently used when the device decoder cannot handle the stream, including the existing 10-bit H.264 path). It does not change hardware decoder selection, output format, frame conversion, timestamps, FFmpeg fast flags, Media3 load control, stream routing or subtitle handling.

**Evidence:** the repository's bundled `libavcodec/codec.h` defines `AV_CODEC_CAP_SLICE_THREADS` and `AV_CODEC_CAP_FRAME_THREADS`; bundled `libavcodec/avcodec.h` explicitly documents the per-thread frame delay. FFmpeg's official `doc/multithreading.txt` describes slice threading as decoding parts of one frame in parallel and documents the frame-thread delay. FFmpeg 8.0's `libavcodec/pthread.c` (the same major release as the repository's bundled libavcodec 62 headers) confirms the runtime selection order: frame threading is selected before slice threading when both flags are requested, provided frame threading is supported and low-delay/chunk restrictions do not disable it. The change therefore removes an unintended preference for the higher-latency mode instead of assuming that setting both flags enables the best mode.

**Verification boundary:** native source and the exact bundled FFmpeg 62 capability definitions were inspected. No native compilation, Gradle task, Android build, tests, CI, decoder playback, device profiling or frame-latency benchmark was run. The effective mode is now logged so the next authorized device test can confirm the selected codec path and compare first-frame latency, sustained frame pacing and dropped frames across representative 10-bit H.264/HEVC sources. No measured performance gain is claimed.


## Playback continuity and diagnostic ownership (23 November 2026)

### Cycle 228 — recover Media3 stuck-player failures at the last valid position

- The Android branch now recognises Media3 1.11.1 StuckPlayerException causes for the explicit no-progress states: buffering-not-loading, buffering-no-progress and playing-no-progress.
- Those failures enter the existing bounded PlaybackReopenSession path and reopen the same source at the current playback position, provided the item has a known positive duration. The existing one-reopen-per-ready-session guard remains in force.
- Suppressed playback is deliberately excluded: transient/permanent suppression can reflect focus, lifecycle or policy state and must not trigger a network reopen. The playing-not-ending state is also excluded because the playhead may already be beyond the declared duration.
- Media3 documented default thresholds are retained; no custom timeout was introduced. This avoids guessing a shorter threshold that could mistake slow but valid sources for a dead player.
- Regression test source covers recoverable no-progress classification, suppression exclusion, one-shot recovery and unknown-duration rejection.

### Cycle 229 — correlate startup load events to the prepared media item

- The diagnostic attempt now records a non-sensitive internal identity for its target media item: stable mediaId when present, otherwise the media URI. The identity is used only for in-memory comparison and is never logged.
- Manifest/media load callbacks now resolve the event-specific EventTime.timeline/windowIndex to the event media item and require it to match the prepare target, in addition to the existing monotonic load-start timestamp check.
- This prevents a prebuffered next episode, a previous media item or an unrelated merged-source event from consuming the current attempt first-load/first-completion slots merely because its callback arrived during the new attempt.
- When Media3 cannot provide a valid event window identity, the callback is rejected for attempts with a known target identity. Attempts with no available target identity retain the existing timestamp-only fallback.
- Regression test source covers matching, mismatched and missing event identities.

### Evidence and verification boundary

- Media3 1.11.1 release notes document native stuck-player detection and default thresholds; see the Android Developers StuckPlayerException API.
- The exact 1.11.1 AnalyticsListener.EventTime source exposes event-specific timeline/windowIndex separately from currentTimeline/currentWindowIndex; this change uses the event-specific pair to identify the media whose load actually emitted the callback.
- The changes preserve the existing Media3 1.11.1 dependency, player buffer policy, retry count, decoder selection, HLS/DASH routing, subtitles and libmpv path.
- Regression tests were added before the implementation commit, but were not executed because no build/test task or CI was authorised. No Gradle compilation, test execution, APK/AAB build, CI or device playback was run. This is remote source review only; runtime recovery and event-correlation behaviour remain unverified until authorised tests and device validation.


### Cycle 230 — audit Media3 1.11.1 video-seek and renderer defaults

- Verified the exact 1.11.1 DefaultRenderersFactory source: AV1 sample-dependency parsing is already enabled by default. It identifies AV1 frames that are not depended on and can accelerate seeks while reducing unnecessary decode work; adding an explicit true setter would be redundant.
- Verified MediaCodecVideoRenderer's default late decoder-input drop threshold is 15,000 microseconds, and DefaultRenderersFactory adopts that default. No tighter value was introduced because doing so without representative frame-pacing measurements could discard useful frames.
- Verified Media3 1.11.0 enables dynamic scheduling by default. The app already enables the renderer duration-to-progress hint on API 31+ where asynchronous MediaCodec operation is the default; this remains gated for older Android versions.
- Did not enable MediaCodec renderer prewarming: the current player prepares one media item at a time, and this app overrides buildVideoRenderers while not providing a matching secondary renderer implementation. Enabling prewarming without the required secondary renderer would be incorrect.
- This cycle intentionally makes no redundant renderer configuration change. It records confirmed upstream defaults and the exact integration boundary so future work does not duplicate already-active optimisations.


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


### Related rendering work — cycles 234–242

The detailed MPV rendering-quality/debanding record (cycles 234–238) and Media3 subtitle-rendering record (cycles 239–242) are maintained in `docs/engineering/codebase-improvement-notes.md`. This player-specific document intentionally links to the canonical engineering history rather than duplicating implementation details.

The subtitle work preserves fractional cue positions and anchors, explicit/measured cue widths, text alignment, embedded window colors and font sizes, and positions captions relative to the fitted video frame for Fit/Fill/Zoom modes. Regression test source was added but not executed under the current no-build authorization boundary.

## Media3 1.12.0-beta01 HDR and decoder follow-up (23 November 2026)

The testing branch now uses Media3 1.12.0-beta01 through the shared version-catalog alias. The concrete upstream changes are HLS `VIDEO-RANGE` PQ/HLG parsing into `Format.colorInfo`, backward-compatible Dolby Vision Profile 7 HEVC fallback with profile-aware decoder selection, and a smaller sample-buffer segment allocation (65,536 to 65,520 bytes). The project minSdk is already 24.

Progressive media now defaults to loading only selected video/image tracks. This can reduce unnecessary sample loading but may add loading when switching to a different progressive video track; this is explicitly in the pre-release device test matrix. HLS adaptive selection and all custom renderer ordering remain unchanged. MediaCodec renderer prewarming remains disabled because the custom renderer factory does not provide a matching secondary renderer.

Added `scripts/tests/test-media3-112-beta-compatibility.sh` to guard catalog alignment, minSdk and known removed APIs. Positive and negative fixture cases were exercised; no Gradle tests, build, CI or device playback was run. The upgrade must not be considered production-validated until the matrix in `docs/superpowers/specs/2026-11-23-media3-112-beta-hdr-decoder-memory-design.md` passes.

Evidence: [Media3 1.12.0-beta01 release notes](https://github.com/androidx/media/releases/tag/1.12.0-beta01), [HLS HDR metadata parsing #3441](https://github.com/androidx/media/issues/3441), [Dolby Vision decoder selection #3486](https://github.com/androidx/media/issues/3486).

## HLS quality metadata visibility (23 November 2026)

The manual HLS quality chooser now carries the playlist's declared PQ/HLG/SDR range and valid frame rate into each option subtitle, alongside codec and bitrate. The canonical implementation, upstream selection caveat and verification boundary are recorded in `docs/engineering/codebase-improvement-notes.md` under “HLS quality chooser — dynamic-range and frame-rate visibility”. This is metadata visibility only: Media3's separate HDR display-capability-aware adaptive-selection issue remains open upstream, and no HDR selection/output behavior was changed.
