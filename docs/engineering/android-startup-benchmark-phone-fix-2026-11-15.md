# Android startup benchmark phone workflow correction — 2026-11-15

## User-visible failure

The first phone bundle installed both APKs successfully, but the phone runner stopped with:

`The matching test APK is not installed or does not target com.nuvio.media.benchmark`.

Device diagnostics showed:
- App under test: `com.nuvio.media.benchmark`
- Self-instrumenting test APK: `com.nuvio.android.baselineprofile`
- Android instrumentation target: `com.nuvio.android.baselineprofile`

This is expected for the `com.android.test` module with
`android.experimental.self-instrumenting=true`. The runner had incorrectly
required the instrumentation target to equal the app-under-test package.
Additionally, the test sources derived the target app package from
`InstrumentationRegistry.getTargetContext()`, which resolves to the
self-instrumenting test APK rather than the Nuvio app.

## Corrections

- The phone runner now resolves the self-instrumenting runner component against
  `com.nuvio.android.baselineprofile`.
- Both Baseline Profile generation and Macrobenchmark explicitly target
  `com.nuvio.media.benchmark`, the isolated application ID used by both
  distribution flavors' benchmark build types.
- The runner automatically stages only the APK pair needed for the selected step, plus itself, into
  `/data/local/tmp/NuvioBenchmark`; users no longer need a separate manual copy
  step to work around Package Manager's inability to install from shared
  Downloads storage.
- Added a single `run <flavor>` command. After installing the profile-capture
  pair and signing in once, it collects the candidate profile, installs the
  benchmark pair, and runs the startup measurements.
- Replacing an APK signed by a previous workflow run is handled safely: only
  `com.nuvio.media.benchmark` and `com.nuvio.android.baselineprofile` may be
  removed on an explicit `INSTALL_FAILED_UPDATE_INCOMPATIBLE` response. The
  production `com.nuvio.media` app is never uninstalled or cleared.
- Updated the generated `START-HERE.txt` instructions to use a fresh
  `NuvioBenchmark-Fixed` folder and the simplified commands.

## Verification

The phone-runner regression test now models the actual self-instrumenting
instrumentation line and checks automatic staging, the test/app package
separation, both distribution flavors, the combined run command, result export,
and the production-package safety guard. The manual workflow also runs shell
syntax validation and the regression test before any Android APK assembly.

## Important measurement distinction

The collected profile text is a candidate output. The Macrobenchmark's
profile-enabled measurement still uses the Baseline Profile packaged in the
benchmark APK. A newly collected candidate is not silently injected into the
already-built APK; review and a separate authorized build are required before
measuring a new profile.


## Follow-up device failure — 2026-11-15

The second real-phone run reached instrumentation successfully but failed only
at the 30-second wait for `nuvio_startup_ready`. The complete uploaded
`instrumentation.log` contains one test failure and no installation or runner
failure. The supplied phone screenshots show the isolated benchmark app had
reached its signed-in Home UI before the repeated attempt; asking the user to
sign in again was therefore not a justified diagnosis.

### Root cause found in the Compose semantics tree

`MainActivity.showContent()` originally put
`.semantics { testTagsAsResourceId = true }` and
`Modifier.testTag("nuvio_startup_ready")` on the same root `Box`. Android's
Compose interoperability guidance defines `testTagsAsResourceId` as a setting
for a semantics subtree and demonstrates placing the `testTag` on a nested
composable below that semantics ancestor. The existing
`By.res("nuvio_startup_ready")` selector was already the documented selector;
changing it or extending the timeout would not correct the tree.

The root `Box` now only enables `testTagsAsResourceId`, and a nested full-size
`Box` carries the conditional readiness test tag. The nested wrapper preserves
the visual layout and does not change when the readiness callback becomes true.

### Failure diagnostics and regression protection

- Added `scripts/tests/test-android-startup-readiness.sh`, which fails if the
  readiness tag is not on a descendant `Box` beneath the resource-ID semantics
  ancestor. The existing phone-runner regression test invokes it.
- Both Baseline Profile collection and the startup Macrobenchmark now include
  the foreground package and an accessibility hierarchy snapshot when the
  readiness wait fails.
- The diagnostic hierarchy redacts all `text` and `content-desc` attribute
  values and is capped at 8,000 characters, avoiding account text in logs.
- Removed misleading timeout guidance that told the user to sign in again.
  Failures now identify the missing marker and provide evidence for the next
  diagnosis.

### Verification boundary

The new structural regression was observed failing against the previous
same-node modifier arrangement. The corrected descendant arrangement is
checked by the regression script. No Android build, device benchmark, or CI run
was started for this correction. The next authorized phone run is still needed
to confirm the marker is now visible through Samsung's accessibility hierarchy
and to produce performance measurements; no startup improvement is claimed.

Reference: [Compose interoperability with UiAutomator](https://developer.android.com/develop/ui/compose/testing/interoperability)


## CI build verification

The corrected phone workflow regression suite passed, and all four APK-pair build
steps passed in GitHub Actions run
[43657203014](https://github.com/jeff25steve/NuvioMobile-Enhanced-test/actions/runs/43657203014).

The resulting phone bundle artifact is:
- Name: `nuvio-android-startup-profile-0a7e3c2e15ebcb59e16e46c80a6b30a392d92f67`
- Artifact ID: `14139057608`
- Size: 688,797,198 bytes
- SHA-256: `7d29794dfdd5fa8f71ae3f5f819e6fd4cbcde43ffea4d114a8133fddd0b22704`
- Expires: 2026-11-29

The build was run from a short-lived verification branch whose only difference
from `main-build-testing` was a temporary push trigger for this workflow. The
APK sources, test sources, phone runner, regression test, and user instructions
were identical to `main-build-testing` at the time of the build.

The bundle has been built and uploaded successfully. A real-device execution
of the corrected instrumentation and startup measurements is still pending;
no startup performance improvement is claimed until the phone results are
collected and compared.
