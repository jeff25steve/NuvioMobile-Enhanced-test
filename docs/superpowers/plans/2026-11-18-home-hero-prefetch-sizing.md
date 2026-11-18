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
- Modify: composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeHeroSection.kt
- Create: composeApp/src/commonTest/kotlin/com/nuvio/app/features/home/components/HomeHeroPrefetchTest.kt
- Test: scripts/tests/test-home-hero-prefetch.sh

**Interfaces:**
- Produces internal data class HeroPrefetchSize(val widthPx: Int, val heightPx: Int).
- Produces internal fun heroLogoPrefetchSize(...): HeroPrefetchSize?, which returns null for non-positive available dimensions and otherwise caps logo width by the effective content width and the 2.6:1 aspect ratio.

- [ ] Step 1: Write failing tests first. Add Kotlin tests for exact logo aspect ratio, content-width fraction, max-width cap, horizontal padding, and invalid/zero available dimensions. Add a source-contract script asserting explicit backdrop size + Scale.FILL and logo size + Scale.FIT.
- [ ] Step 2: Verify RED. Run sh scripts/tests/test-home-hero-prefetch.sh; it must fail because the prefetch requests are currently unbounded. Run focused Kotlin test only after an authorized test/build session; no build is authorized now.
- [ ] Step 3: Implement minimal sizing. Calculate backdrop request size from heroWidthPx/heroHeightPx. Calculate logo width from available horizontal content after the same bar/edge padding used by rendered content, then apply contentWidthFraction, contentMaxWidth, and logoWidthFraction; derive logo height using 2.6:1.
- [ ] Step 4: Verify GREEN. Re-run the source-contract script. Re-fetch changed Kotlin test and source to verify expected geometry/API use. Record that Kotlin compilation and unit tests remain pending because builds are not authorized.
- [ ] Step 5: Commit. perf(home): bound hero neighbour image prefetch sizes.

### Task 2: Skip unused card-style logo prefetch

**Files:**
- Modify: composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeHeroSection.kt
- Modify: scripts/tests/test-home-hero-prefetch.sh
- Modify: docs/engineering/android-startup-critical-path-review.md

**Interfaces:**
- Consumes HeroPrefetchSize and heroLogoPrefetchSize from Task 1.

- [ ] Step 1: Extend the failing source-contract test. Require logo enqueue to be guarded by the same !isCardStyle condition used by the rendered HeroContentBlock branch.
- [ ] Step 2: Verify RED. Run the focused shell test and confirm it fails on the unconditional logo prefetch.
- [ ] Step 3: Implement the layout guard. Keep backdrop prefetch unconditional when a URL exists; enqueue logo requests only for full-bleed hero style and only when a valid bounded logo size is available.
- [ ] Step 4: Verify GREEN. Run the focused shell test and re-fetch all changed files. Confirm the catalogue prefetcher and visible AsyncImage declarations are unchanged.
- [ ] Step 5: Update notes. Record both cycles, exact Coil 3.6.3 rationale, test coverage, and the no-build/no-device validation boundary.
- [ ] Step 6: Commit. perf(home): skip hidden card-hero logo prefetch.

## Verification boundary

The shell contract can be run without Gradle. Kotlin unit tests require an authorized Gradle test invocation and remain pending. A physical-device trace is required to quantify reduced decode dimensions, memory use, and any impact on slide-transition latency.