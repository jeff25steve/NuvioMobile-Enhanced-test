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
