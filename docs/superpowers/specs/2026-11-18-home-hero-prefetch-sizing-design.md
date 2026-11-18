# Home hero image prefetch sizing

Date: 18 November 2026
Target: main-build-testing

## Intent

Bound neighbour-image prefetch work in the Home hero to the dimensions at which those images are rendered. Prevent the card-style hero from prefetching logo artwork that its layout does not display. The goal is to avoid full-resolution decodes, memory pressure and redundant network work without changing visible artwork, paging, trailer behavior, catalogue artwork, or the existing neighbour-prefetch policy.

## Current behavior confirmed on the target branch

- HomeHeroSection.kt draws each visible hero backdrop with AsyncImage, Modifier.fillMaxSize(), and ContentScale.Crop inside the measured hero viewport.
- The neighbour-prefetch effect uses ImageRequest.Builder(platformContext).data(url).build() for both backdrop and logo URLs, so those requests do not inherit the visible composable's measured constraints.
- Coil 3.6.3 defaults a bare ImageRequest.Builder to Size.ORIGINAL; its AsyncImage composable uses constraints-aware sizing.
- Full-bleed hero content shows the item logo in HeroContentBlock with a 2.6:1 aspect ratio. Card-style hero content intentionally omits that content block, but the prefetch effect currently queues logos for both styles.
- Catalogue poster prefetch already calculates rendered pixel dimensions in PosterImagePrefetch.kt; it is out of scope and must remain unchanged.

## Approaches considered

1. Bound requests to their visible layout dimensions (chosen). Use the same hero viewport bounds for backdrop prefetch and the actual full-bleed content constraints for logo prefetch. Match ContentScale.Crop / Fit in the request. This retains the current prefetch timing and cache path while preventing original-size requests.
2. Disk-only preloading. This avoids bitmap decoding during prefetch, but does not provide a decoded image for an immediately visible neighbour and may add decode latency at transition time. It is not selected without device trace evidence.
3. Remove hero neighbour prefetch. This would eliminate prefetch CPU/network cost but risks making the next hero page visibly late. It is not selected because there is already a useful one-page-ahead interaction design and no evidence that the whole mechanism is harmful.

## Scope and constraints

- Modify only Home hero prefetch request construction and associated focused tests.
- Keep the two-neighbour prefetch policy, metadata fetch behavior, active-image AsyncImage rendering, artwork URL selection, and trailer logic unchanged.
- Do not add dependencies, alter Coil version, introduce a second image loader, or change the catalog poster prefetcher.
- Card style must not enqueue logo requests because that logo is not displayed in that layout.
- Invalid/zero computed logo dimensions must skip that optional prefetch, not create an unbounded fallback request.
- No Gradle build, CI, emulator or device benchmark is authorized in this task.

## Success criteria

- Backdrop neighbour requests specify positive pixel width/height matching the measured hero viewport and use the same crop scale as the visible backdrop.
- Full-bleed logo neighbour requests specify positive pixel bounds derived from available hero content width, layout maximum width, content-width fraction and logo-width fraction, with the 2.6:1 logo aspect ratio and fit scale.
- Card-style neighbour requests omit logos.
- Focused pure Kotlin tests cover pixel sizing, width limits, invalid dimensions, target scale intent, and omission of the card-style logo. The Compose call-site mapping to Coil Scale.FILL/FIT is verified by source review.
- Notes distinguish the expected resource reduction from measured runtime performance; no speedup claim is made without device traces.