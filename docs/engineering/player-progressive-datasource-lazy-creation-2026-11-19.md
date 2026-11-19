# Progressive playback DataSource creation deferral — 19 November 2026

## Finding

The Android buffering decorator eagerly constructed both underlying data sources every time Media3 requested a DataSource: one plain upstream source and one cache/read-ahead source. The actual URL decision was deferred until `open(DataSpec)`, so one of those two objects was guaranteed to be unused for each open. When VOD disk caching was enabled, constructing the unused/selected cache-backed source also called `PlaybackDiskCache.get()` and built a `CacheDataSource` during factory creation.

The pinned Media3 1.8.0 source shows that `ProgressiveMediaSource.createPeriod()` creates the DataSource from its factory on the playback thread. Progressive loading then invokes `DataSource.open()` from the `ProgressiveMediaPeriod` loadable running on a Loader worker. This gives a safe boundary: return a lightweight selector from the factory, and instantiate only the selected upstream/cache data source when `open()` is called.

## Changes integrated

### Cycle 64 — cache initialization threading investigation

- Investigated `SimpleCache` construction and the exact pinned 1.8.0 implementation.
- The first implementation briefly introduced a second background-initialization wrapper. Reviewing the source more carefully showed that Media3's `SimpleCache` constructor itself starts `ExoPlayer:SimpleCacheInit` and opens its constructor condition variable before the full `initialize()` directory/index scan. The constructor does not synchronously wait for the full scan.
- Removed that redundant wrapper in a follow-up correction. No extra cache executor or prewarm thread remains in the final implementation.

### Cycle 65 — create only the selected DataSource

- `SelectiveBufferedDataSource` now receives the plain and buffered factories rather than already-created DataSource instances.
- It creates the plain or buffered DataSource only when `open(DataSpec)` identifies which path is needed.
- Transfer listeners registered before `open()` are retained and attached to the selected DataSource before its first open. The selected instance is reused across subsequent open/close cycles, matching the previous reuse behaviour.
- For progressive media, the cache-backed factory is now invoked from `DataSource.open()` on the Media3 Loader worker rather than eagerly while `ProgressiveMediaSource.createPeriod()` is assembling the period on the playback thread.
- This also avoids creating a cache-backed DataSource at all for a URL that does not match the buffered URL set.

### Cycle 66 — regression contracts

Added `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/SelectiveBufferedDataSourceTest.kt` covering:

- Neither underlying DataSource is created before `open()`.
- A buffered URL creates only the buffered/cache-backed source.
- An unbuffered URL never initializes the cache-backed source.
- The selected DataSource instance is reused across open/close cycles.

## Compatibility boundaries

- The same `PlaybackDiskCache` singleton and one-instance-per-directory rule are retained.
- Cache directory, auto-size calculation, configured size, `LeastRecentlyUsedCacheEvictor`, `StandaloneDatabaseProvider`, and `CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR` are unchanged.
- The same `bufferedUrls` matching rule chooses the cache/read-ahead path; URL routing, HTTP headers, retries, response headers, subtitles, media selection and buffer thresholds are unchanged.
- HLS/DASH/live/loopback/YouTube exclusions remain governed by the existing `bufferedUrls` construction. This change does not route adaptive or live streams through the VOD cache.
- No network request is moved, skipped or altered by this change.

## Evidence and expected effect

- [Media3 1.8.0 ProgressiveMediaSource](https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaSource.java): `createPeriod()` creates the DataSource from its factory.
- [Media3 1.8.0 ProgressiveMediaPeriod](https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java): the progressive loadable performs `DataSource.open()` in its Loader load operation.
- [Media3 1.8.0 SimpleCache](https://github.com/androidx/media/blob/1.8.0/libraries/datasource/src/main/java/androidx/media3/datasource/cache/SimpleCache.java): constructor starts a separate initialization thread and opens the condition variable before scanning/index initialization. The implementation deliberately does not claim that the constructor waits for the full scan.
- The concrete reduction is one unused DataSource object per open, plus moving selected cache-backed DataSource construction and its cache-singleton lookup off the player playback-thread factory call and onto the media loading path.
- This is a source-level reduction in work on the playback-thread boundary, not a measured first-frame or millisecond claim. Physical-device comparison is still required.

## Verification status

- Source was reviewed against the repository's pinned Media3 1.8.0 implementation and the updated remote call sites.
- Three focused Robolectric/host-test contracts were added. They have not been executed.
- Kotlin compilation, Gradle tests, APK/AAB builds, Macrobenchmark, CI, emulator and physical-device playback were not run. No real playback speedup or regression-free runtime result is claimed.
- The temporary FutureTask/prewarm experiment was removed after source inspection clarified SimpleCache's own initialization threading. The final code relies on the existing Media3 cache singleton and moves its creation to the selected DataSource's `open()` path.

