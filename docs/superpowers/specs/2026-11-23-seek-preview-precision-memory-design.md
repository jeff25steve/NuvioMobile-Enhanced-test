# Seek-preview precision and memory bounds

## Intent

Make timeline frame previews more trustworthy during scrubbing and keep their retained bitmap memory bounded on long sessions and unusual aspect ratios.

## Goals

- Reduce fallback frame quantization from up to five seconds to at most two seconds.
- Do not show a cached frame that is more than one maximum bucket away from the requested position.
- Bound both native-thumbnail and decoded-frame caches by entry count and estimated RGBA bytes.
- Preserve the existing native thumbnail feed, ExoPlayer fallback decoder, HTTP headers, MPV playback path, and iOS implementation.

## Non-goals

- Replacing the custom ExoFrameGrabber with Media3 FrameExtractor. The current implementation already has request coalescing, a dedicated decoder, custom headers, native thumbnail fallback, and a carefully controlled GL readback path. Replacing it before a parity audit would add risk without proving a user-visible improvement.
- Changing playback decoder selection, HDR playback output, player buffering, refresh-rate policy, or playback surface.
- Adding dependencies or changing Media3 versions.

## Design decisions

- Use 1–2 second frame buckets (duration-derived, clamped to 1,000–2,000 ms) for the fallback decoder.
- Restrict reuse of cached fallback frames to a 2,000 ms distance. If no suitably close frame exists, show the existing loading state rather than a visibly unrelated frame.
- Keep the existing 100-entry LRU ceiling and add a shared 24 MiB estimated RGBA bitmap budget to both seek-preview caches.
- Estimate bitmap memory as width × height × 4 bytes. This is an explicit cache policy estimate, not a measurement of total process or GPU memory.
- Evict oldest entries first; preserve the existing remove-and-reinsert LRU refresh on cache hits.

## Approaches considered

1. Replace the custom frame grabber with Media3 FrameExtractor. Rejected for this cycle because the official API defaults to software decoding, is UnstableApi, and its documented color caveats and custom MediaSource/header parity require a separate migration and device validation.
2. Increase frame extraction frequency without changing cache policy. Rejected because it would increase decode and memory work without fixing inaccurate stale-frame reuse.
3. Tighten fallback bucket precision and bound both caches. Selected because it directly fixes observable preview accuracy and predictable memory retention without changing playback engines or dependencies.

## Constraints

- Target only `main-build-testing`.
- No Gradle tests, compilation, builds, CI, benchmarks, or device playback without explicit authorization.
- Add regression tests, but report them as unexecuted under the current authorization boundary.
