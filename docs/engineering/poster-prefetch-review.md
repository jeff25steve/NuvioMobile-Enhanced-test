# Poster image prefetch review

Date: 12 November 2026
Branch: `main-build-testing`

## Scope and decision

This review evaluates the poster-pipeline findings supplied from Grok against the branch source. It does not continue the Macrobenchmark work. The Macrobenchmark workflow and device measurements remain paused until the wider performance goals are agreed and separately authorised.

One contained improvement was selected: prefetch the actual artwork used by the Continue Watching shelf, with the same Coil request-model builder and exact image-region dimensions as its displayed cards. Home collections, the vertical poster grid, and the hero pager are intentionally outside this change because they use different display paths, request keys, or image geometry.

## Findings checked against source

1. **Prefetch and display request mismatch — not established for home catalog posters.** `PosterRowImagePrefetchEffect` and `NuvioPosterCard` already call the same `posterImageModel` helper, including the custom-fallback cache-key extra. Coil 3.6.3 `AsyncImage` resolves size from measured constraints; its memory-cache implementation validates cached bitmap dimensions against the later request (including one-pixel tolerance for downsampling rounding). An explicit prefetch size alone does not prove a guaranteed second network/decode. Exact size disagreement can still reject a cache entry, so use the rendered image bounds wherever they are known.

2. **Two-item skip — not a demonstrated cold-load bug.** The shared `NuvioShelfSection` supplies `ShelfRowCacheWindow` with an ahead window of 0.5 viewport and a behind window of 0.25 viewport. Compose prepares items within the lazy-layout cache window. The separate Coil prefetch intentionally starts beyond the immediate neighbours; changing the skip count without profiling could duplicate work already performed by Compose. Keep the existing count for now.

3. **Only home catalog rows use the poster prefetcher — confirmed, but scope matters.** Continue Watching is another horizontal shelf with Coil artwork and previously had no image prefetch. It is a good next surface because its image request can be aligned with the existing poster-prefetch model. Home collections use a platform-specific Android cache key (`home-collection:<url>`) and an iOS animation-aware path; simply reusing the generic poster request would not be cache-equivalent. Poster Grid is a vertical grid, and Hero is a pager with different geometry and visibility; they need separate targeted investigations.

4. **Three parallel request builders — screenshot content is truncated.** The visible evidence is insufficient to identify all three implementations or their alleged divergence. For Continue Watching, the duplicate fallback-aware builder was verifiably equivalent to `posterImageModel`; this change removes that duplication and uses the shared helper. No broader request-builder rewrite is included.

## Implementation in this cycle

- Extended `PosterPrefetchTarget` with an optional explicit DP image size; existing catalog targets preserve their original shape-derived sizing.
- Continue Watching now derives the selected artwork URL and fallback using the same precedence as each of its Card, Wide, and Poster renderers, including the Torbox display-artwork URL mapping.
- Its prefetch size matches the actual image region for each style: full landscape card, wide artwork strip, or poster image bounds.
- Exiting/disintegrating entries are excluded from prefetch.
- Continue Watching display requests now reuse `posterImageModel`, eliminating a duplicate implementation of the fallback-aware Coil request builder.
- Added common unit tests for all three styles, fallback selection, exact target bounds, and empty artwork.

## Validation and limits

- Source was inspected at the remote branch head before editing and Coil 3.6.3 implementation/docs were checked.
- No Gradle build, tests, Macrobenchmark run, workflow dispatch, or CI run was executed. This is intentional per the user's instruction; the new tests have not been executed and no runtime improvement is claimed.
- The commit uses GitHub's `[skip ci]` push/pull-request workflow skip marker. This does not suppress manually dispatched workflows; none was dispatched.
- Next validation, when authorised, is run the focused `HomeContinueWatchingArtworkTest` and verify startup/scroll behavior on a physical Android device before expanding prefetch to more surfaces.

## References

- Coil 3.6.3 AsyncImage constraints: https://github.com/coil-kt/coil/blob/3.6.3/coil-compose-core/src/commonMain/kotlin/coil3/compose/AsyncImage.kt
- Coil 3.6.3 memory-cache size validation: https://github.com/coil-kt/coil/blob/3.6.3/coil-core/src/commonMain/kotlin/coil3/memory/MemoryCacheService.kt
- Compose LazyLayout cache window: https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/layout/LazyLayoutCacheWindow
- Compose LazyRow: https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyRow.composable
- GitHub workflow skip markers: https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs

## Backend readiness follow-up — 12 November 2026

The early-2027 credential rotation remains unverified. The existing Full-build self-host discovery path has been source-reviewed and hardened rather than replaced. See [backend-migration-readiness.md](backend-migration-readiness.md) for the risk assessment, alternative strategies, security findings and remaining work.
