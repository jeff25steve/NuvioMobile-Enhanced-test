# MetaDetails Parser Allocation Reduction Plan

> **For agentic workers:** Follow the two tasks in order. Each task has its own regression coverage and implementation change.

**Goal:** Reduce avoidable intermediate collection materialization in MetaDetailsParser while preserving all existing parsed metadata values and ordering.

**Architecture:** Keep the existing parser and public MetaDetails output unchanged. Replace director/writer list concatenation and chained transformations with one ordered deduplication pass; replace season-poster list/pair transformations with direct set/map builders and explicit loops.

**Tech Stack:** Kotlin 2.4.10, kotlinx.serialization JSON tree API already used by the common parser.

**Spec:** Existing behavior in `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsParser.kt`, characterized by `MetaDetailsParserTest.kt` and `SeasonPosterParsingTest.kt`.

## Global Constraints

- No dependency, public API, JSON schema or UI changes.
- Preserve exact first-seen ordering and case-sensitive credit-name deduplication after trimming.
- Preserve keyed season poster precedence, numeric-key collision behavior, specials handling, null poster slots and one-based fallback.
- Do not run Gradle, builds, tests, benchmarks or CI without explicit authorization.
- Add tests before production changes; report that red/green execution was not possible under the no-build constraint.

## Review Focus

- Credit names that differ only by case must remain distinct; exact normalized duplicates must keep the first position.
- Empty/whitespace names must remain excluded without changing other credits.
- Numeric season keys with different spellings that parse to the same integer must retain last-value-wins behavior.
- Null, blank and non-primitive season poster values must remain omitted.
- Season ordering and specials inference must remain unchanged for sparse and duplicate episode seasons.

---

### Task 1: Ordered director/writer name merge

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsParser.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/details/MetaDetailsParserTest.kt`

**Interfaces:** Keep `MetaDetailsParser.parse` and its output fields unchanged. Add a private merge helper that accepts the three ordered name groups and returns a list.

- [x] Add a parser-level regression test asserting trimmed first-seen order, exact duplicate removal and case-sensitive distinction for both directors and writers.
- [x] Implement direct ordered accumulation with a membership set and one result list; remove the concatenation/map/filter/distinct chains.
- [x] Re-read the changed method and test fixture against the original ordering and blank-name rules. Test execution remains blocked until authorized.

### Task 2: Season poster map construction

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsParser.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/details/SeasonPosterParsingTest.kt`

**Interfaces:** Keep `seasonPosters(videos)` and `parseKeyedSeasonPosters(element)` private and preserve their returned `Map<Int, String>` contract.

- [x] Add a parser-level regression test for numeric key aliases, last-value-wins, invalid keys and blank/non-primitive values.
- [x] Gather unique valid episode seasons in one set pass, sort once, derive positive-season count without a separate filtered list, and build the result map directly.
- [x] Build keyed season poster maps directly with `buildMap`, retaining numeric-key overwrite behavior.
- [x] Re-read all existing season-poster cases and the changed code. Test execution remains blocked until authorized.

## Verification Boundary

Static GitHub source review and remote commit comparison are permitted. No compilation, tests, benchmarks or runtime performance claims are permitted without explicit authorization. The result may be described only as fewer explicit intermediate collection transformations, not as a measured speed improvement.