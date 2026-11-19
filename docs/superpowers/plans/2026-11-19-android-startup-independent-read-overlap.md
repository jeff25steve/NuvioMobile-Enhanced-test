# Android startup independent-read overlap Implementation Plan

> **For agentic workers:** Use the native execution workflow for this plan. Steps use checkbox syntax.

**Goal:** Overlap only Continue Watching preference and Home snapshot restoration with the existing sequential watched-history and watch-progress startup reads.

**Architecture:** A small internal coroutine helper launches independent reads as structured children on an injected dispatcher, while running the dependent sequence on that dispatcher. The startup preloader keeps the two tracking-dependent reads ordered and preserves existing step-level failure isolation.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines 1.10.2, Android SharedPreferences, AppFileCache.

**Spec:** `docs/superpowers/specs/2026-11-19-android-startup-independent-read-overlap-design.md`

## Global Constraints

- Do not move Home-required data beyond the first frame.
- Keep watched history and watch progress sequential.
- Keep all blocking reads off Main.
- Preserve `step()` error isolation and cancellation propagation.
- Do not change dependencies, iOS startup, UI or readiness markers.
- No Gradle tasks, builds, device benchmarks or CI in this task.

## Review Focus

- Both independent reads start before the dependent sequence finishes.
- A failure/cancellation in the structured scope does not leak child work.
- Existing `step()` handling still logs a failed read and allows the other reads to complete.
- No watched/progress registry initialization is moved into a concurrent child.
- Main-thread execution is not introduced by the helper.

---

### Task 1: Testable startup read scheduling

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/core/startup/StartupPreloader.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/core/startup/StartupPreloaderTest.kt`

**Interfaces:**
- Produce `runStartupPreloadWork(dispatcher: CoroutineDispatcher, sequential: suspend () -> Unit, independent: List<suspend () -> Unit>)`.
- The helper launches each independent operation as an `async(dispatcher)` child, yields so they can start, executes `sequential` with `withContext(dispatcher)`, then awaits all independent children.
- Structured scope cancellation and child-failure propagation remain standard kotlinx.coroutines behavior.

- [x] Add a test using `StandardTestDispatcher`, a `CompletableDeferred` gate and `runCurrent()`. Assert both independent operations have started while the sequential operation is still waiting at the gate.
- [x] Review the test source and confirm it exercises the scheduling contract, not just a mocked call count.
- [x] Add the helper with injected dispatcher and structured child jobs.
- [x] Replace the four sequential startup reads with two independent lambdas (Continue Watching preferences and Home snapshot) plus the ordered watched/progress sequence.
- [x] Preserve each existing `step()` wrapper and the existing surrounding profile/download preload ordering.
- [x] Perform static review of imports, call sites, cancellation and failure behavior. Do not run Gradle, tests, builds or CI.

### Task 2: Engineering record and remote verification

**Files:**
- Modify: `docs/engineering/android-startup-critical-path-review.md`
- Modify: `docs/engineering/codebase-improvement-notes.md`

- [x] Record the selected bounded overlap, why tracking-dependent reads remain sequential, and why this is an expected optimization rather than a measured gain.
- [x] Record that regression tests are added but unexecuted.
- [x] Fetch the updated files and branch ref from GitHub.
- [x] Compare the new branch head with the starting checkpoint and confirm zero commits behind.
- [x] Confirm no workflow file changed and no CI/build was started.

## Execution record

- Test source added before the production helper. The test was statically reviewed, but the required RED/GREEN run was not performed because this task was constrained to no Gradle tasks/builds/CI. Therefore the test and Kotlin compilation remain unverified; this plan is not a substitute for an authorized test run.
- Static self-review checked dispatcher placement, structured child cancellation, failure isolation through existing `step()` wrappers, the watched-before-watch-progress ordering, Android-only invocation and unchanged iOS startup.
- No independent reviewer/subagent was available in this session; review was author-performed.
- Remote branch comparison from starting checkpoint `12de97ca85155e12e806f762af439cfe8859d0e4` to the final head confirms ahead/behind status after the last commit. No workflow file was changed and no new Actions run was observed.
