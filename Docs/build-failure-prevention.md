# Build Failure Prevention Notes

This file records concrete failures discovered during Nuvio Mobile Enhanced development so future changes are checked against them before starting another build.

## 2026-10-03 — Codemagic Android Full Debug failure

Codemagic workflow: `android-full-debug`  
Branch: `feature/whats-new`  
Commit built: `a6811971aeb634b50564dd87b5551485deece3ff`  
Failure stage: Kotlin/Gradle compilation during **Build Android Full Debug APK**.

### Root cause

`SettingsScreen.kt` referenced a symbol named `whatsNewSettingsContent` for both mobile and tablet settings pages, but the What's New implementation actually exposed `WhatsNewSettingsScreen` and did not define the referenced `LazyListScope` content function.

Affected call sites were the mobile and tablet `SettingsPage.WhatsNew` branches.

This is a source/API contract mismatch: a caller was wired to a function that did not exist.

### Secondary diagnostics

The compiler also reported unresolved `Res.string.whats_new_*` references inside `WhatsNewScreen.kt`. The corresponding entries are present exactly once in:

`composeApp/src/commonMain/composeResources/values/strings.xml`

Therefore the next clean Codemagic build must explicitly confirm that the Compose Multiplatform resource accessors are generated correctly after the missing-function error is removed. Do not assume a resource accessor failure is real until the primary Kotlin symbol errors have been eliminated and the build is rerun.

### Prevention rules

Before adding or changing a settings page:

1. Search every caller and callee for the exact function/symbol name.
2. Verify the implementation signature matches the architecture of the caller (full screen composable versus `LazyListScope` content).
3. Trace mobile and tablet call paths separately.
4. Verify all newly referenced Compose resources exist in the active `strings.xml` source set.
5. Review the final diff for unresolved references before starting CI.
6. Run the exact Codemagic workflow for the affected distribution; do not use GitHub Actions while the project CI restriction is active.
7. Treat compiler diagnostics from the first clean build as authoritative and fix the earliest real source error before interpreting cascading errors.

## 2026-10-03 — Codemagic Android Full Debug failure: generated Compose resources missing

Codemagic run #8 reached Kotlin compilation, but the compiler reported unresolved generated accessors such as `Res.string.whats_new_since_unavailable`, `Res.string.whats_new_refresh_failed_cached`, `Res.string.whats_new_since_one_release`, `Res.string.whats_new_since_releases`, and `Res.string.whats_new_status_newer_releases_available`.

The source strings were verified in `composeApp/src/commonMain/composeResources/values/strings.xml`. They are legal Compose resource identifiers and exist exactly once, so the failure is not explained by a missing XML declaration.

The Compose Multiplatform pipeline generates Kotlin accessors through `generateResourceAccessorsForCommonMain`. Current KMP/Gradle ecosystems have documented cases where generated resource outputs are consumed without an explicit task dependency, producing incorrect build results.

### Prevention now implemented

- `composeApp/build.gradle.kts` explicitly makes Kotlin compilation depend on the common Compose resource generation tasks:
  - `generateComposeResClass`
  - `generateResourceAccessorsForCommonMain`
  - `generateExpectResourceCollectorsForCommonMain`
- `codemagic.yaml` now has a preflight step that force-runs `generateResourceAccessorsForCommonMain` and verifies that the generated output contains a What’s New accessor before the expensive APK compile step begins.
- Do not treat `Res.string.*` errors as missing XML until the generated accessor output has been checked.
- After any KMP/AGP/Compose upgrade, re-check task dependency wiring for generated source directories.


## Editing-process lesson

An intermediate automated edit added unmatched/extra closing braces while trying to wrap the missing What's New page. The file was subsequently checked and repaired, but this demonstrates that large text-based rewrites must be followed by:

- a structural/braces check,
- a focused diff review,
- an exact-symbol search,
- and only then a CI build.

Do not rely on a successful file-write operation as evidence that the resulting Kotlin source is structurally correct.

## 2026-10-03 — Build #9 / resource preflight investigation

The direct `generateResourceAccessorsForCommonMain` preflight did not prove the complete resource pipeline was correct. Compose Multiplatform's own implementation shows that `generateResourceAccessorsForCommonMain` depends on the prepared resources produced by `prepareComposeResourcesTaskForCommonMain`, and JetBrains integration tests validate the combined generation pipeline through the project build/import tasks.

Prevention: validate the complete `prepareKotlinIdeaImport` resource-generation graph instead of assuming the leaf accessor task alone is sufficient. Do not add broad `dependsOn` edges from every Kotlin compilation unless the project's actual task graph demonstrates they are required; Gradle recommends wiring producer outputs/inputs so dependencies are modeled by the generating task itself.

## 2026-10-03 — Resource generation must be unconditional for this module

The What’s New strings are consumed directly by commonMain source. Compose Multiplatform documents that `generateResClass = auto` only generates the resource class when the resource library is an explicit dependency, while `generateResClass = always` unconditionally enables generation. This module already depends on Compose resources, but the repeated CI failures justify making the requirement explicit rather than relying on automatic detection.

