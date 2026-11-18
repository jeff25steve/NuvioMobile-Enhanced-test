# Home Hero Prefetch Sizing Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement the plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Bound Home hero neighbour prefetch requests to rendered pixel sizes and avoid queuing logo requests for a layout that does not render logos.

**Architecture:** Keep the existing HomeHeroSection neighbour-prefetch effect and Coil singleton loader. Add a small pure sizing helper for logo geometry, apply explicit Coil pixel sizes and matching scale modes, and guard logo prefetch behind the existing full-bleed layout condition.

**Tech Stack:** Kotlin, Compose Multiplatform 1.13.0-beta01, Coil 3.6.3, Kotlin test.

**Spec:** docs/superpowers/specs/2026-11-18-home-hero-prefetch-sizing-design.md

## Global Constraints

- Do not change visible AsyncImage rendering, hero paging, trailers, or metadata prefetch.
- Do not change catalogue poster prefetch or dependencies.
- Use Coil 3.6.3 ImageRequest.Builder.size(width, height) and scale(Scale).
- Keep card-style artwork prefetch; only omit its unused logo prefetch.
- No Gradle builds or CI.

## Review Focus

- Zero or invalid hero/content dimensions: optional logo prefetch must be skipped, not converted back to original-size loading.
- Narrow portrait viewport: computed logo bounds must remain positive and within available content width.
- Tablet landscape: content width must respect contentMaxWidth.
- Card style: logo requests must not be enqueued.
- Changes during pager settle: neighbour list, URL selection and metadata-fetch sequencing remain unchanged.

---

### Task 1: Bound hero backdrop and logo request dimensions

**Files:**
- Create: composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HeroImagePrefetch.kt
- Modify: composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeHeroSection.kt
- Create: composeApp/src/commonTest/kotlin/com/nuvio/app/features/home/components/HomeHeroPrefetchTest.kt

**Interfaces:**
- Produces internal data class HeroPrefetchSize(val widthPx: Int, val heightPx: Int), HeroPrefetchTarget(url, size, scale), and pure target/sizing helpers in HeroImagePrefetch.kt.
- Produces heroLogoPrefetchSize(...): HeroPrefetchSize?, which returns null for non-positive available dimensions and otherwise caps logo width by the effective content width and the 2.6:1 aspect ratio.

- [ ] Step 1: Write failing Kotlin tests first. Cover backdrop viewport bounds, full-bleed logo bounds, content-width fraction, max-width cap, horizontal padding, 2.6:1 aspect ratio, invalid dimensions, and omission of card-style logos.
- [ ] Step 2: Verify RED. Compile the focused helper/test fixture with kotlinc and confirm it fails to resolve the missing helper API. No Gradle build is authorized.
- [ ] Step 3: Implement minimal pure helpers in HeroImagePrefetch.kt. Calculate backdrop request size from heroWidthPx/heroHeightPx. Calculate logo width from available horizontal content after the same bar/edge padding used by rendered content, then apply contentWidthFraction, contentMaxWidth, and logoWidthFraction; derive logo height using 2.6:1.
- [ ] Step 4: Verify GREEN. Compile the actual helper source with the focused local Kotlin fixture. Re-fetch changed Kotlin test and source to verify the production call site uses the tested helper. Record that the Gradle commonTest task remains pending because builds are not authorized.
- [ ] Step 5: Commit. perf(home): bound hero neighbour image prefetch sizes.

### Task 2: Skip unused card-style logo prefetch

**Files:**
- Modify: composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeHeroSection.kt
- Modify: composeApp/src/commonTest/kotlin/com/nuvio/app/features/home/components/HomeHeroPrefetchTest.kt
- Modify: docs/engineering/android-startup-critical-path-review.md

**Interfaces:**
- Consumes HeroPrefetchSize and heroLogoPrefetchSize from Task 1.

- [ ] Step 1: Extend the failing Kotlin behavior test to prove card-style targets never include the supplied logo URL.
- [ ] Step 2: Verify RED. Compile/run the focused helper fixture and confirm the card-style expectation fails with the current unconditional target selection.
- [ ] Step 3: Implement the layout guard. Keep backdrop prefetch unconditional when a URL exists; enqueue logo requests only for full-bleed hero style and only when a valid bounded logo size is available.
- [ ] Step 4: Verify GREEN. Compile/run the focused helper fixture and re-fetch all changed files. Confirm the catalogue prefetcher and visible AsyncImage declarations are unchanged.
- [ ] Step 5: Update notes. Record both cycles, exact Coil 3.6.3 rationale, test coverage, and the no-build/no-device validation boundary.
- [ ] Step 6: Commit. perf(home): skip hidden card-hero logo prefetch.

## Verification boundary

The pure helper will be compiled and exercised with a local kotlinc fixture; the Compose Multiplatform commonTest task still requires an authorized Gradle test invocation. A physical-device trace is required to quantify reduced decode dimensions, memory use, and any impact on slide-transition latency.