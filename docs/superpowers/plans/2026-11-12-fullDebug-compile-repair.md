# FullDebug Compile Repair — 12 November 2026

**Goal:** Correct all compiler diagnostics reported by fullDebug run #121 against the latest `main-build-testing` branch, without restoring the retired app-background host or changing `enhanced`.

**Method:** Superpowers systematic debugging, test-driven development, code review, and verification-before-completion. The failed fullDebug compiler output is the concrete regression evidence. The user authorised a fresh CI fullDebug build; no local build/test suite is being run.

## Scope

- Fix the Rust `ok_or_else` closure in Matroska sample reading.
- Remove stale consumers of the deleted legacy app-background package.
- Select the native or glass-capable bottom sheet using current Liquid Glass `BOTTOM_SHEETS` state and safely check nullable backdrop readiness.
- Replace the removed software-bitmap helper with Coil 3.6.3's documented `allowHardware(false)`.
- Fix network-gate named-argument and PIN result message constructors.
- Import the extracted `AuthState` model and correct visibility for methods exposing internal `AuthSessionIdentity`.
- Update existing engineering notes with the failed-run evidence and checkpoint.

## Review checklist

- [x] Map the complete run #121 Rust and Kotlin diagnostics to source causes.
- [x] Remove obsolete app-background consumers instead of restoring the retired module.
- [x] Consult Context7 for version-specific Coil API and Kotlin/Compose documentation; use Exa to cross-check the Compose API diagnosis.
- [x] Apply targeted compiler fixes without dependency upgrades or changes to `enhanced`.
- [x] Update existing engineering notes and this plan.
- [ ] Push to `main-build-testing` using an expected-head, non-force update.
- [ ] Refresh an isolated CI branch from that exact target head and run the same fullDebug workflow.
- [ ] Record the final Actions conclusion and APK artifact availability.

## Verification boundary

Run #121 failed and produced no APK. The repair is source-reviewed but not build-verified. Do not report it as passing until the new GitHub Actions run completes successfully and the APK artifact is present.
