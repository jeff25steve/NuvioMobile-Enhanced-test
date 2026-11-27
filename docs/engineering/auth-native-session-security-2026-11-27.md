# Native Supabase Session Security — 2026-11-27

## Scope

This change upgrades the Supabase Kotlin client and replaces its default plaintext multiplatform-settings session persistence with native platform-protected storage. It preserves persistent sign-in; it does not use memory-only sessions and does not add biometric prompts.

Target branch: `auth-native-session-security`, based on `main-build-testing` at `023c81aeb5d23ae0335706f24c369126e2289599`.

## Dependency alignment

The application dependency catalog is aligned with Supabase Kotlin 3.9.0:

- Supabase Kotlin: 3.4.1 → 3.9.0
- Ktor: 3.4.1 → 3.6.0
- kotlinx.coroutines: 1.10.2 → 1.11.0
- kotlinx.serialization: 1.10.0 → 1.11.0

These versions match the Supabase Kotlin 3.9.0 version catalog. Kotlin remains 2.4.21. The 4.0 alpha is deliberately not used in the production dependency graph.

## Native storage

### Android

- Session JSON is encrypted using AES-256-GCM.
- The AES key is non-exportable and held by Android Keystore.
- Ciphertext is stored in the app-private `nuvio_secure_supabase_sessions` SharedPreferences file.
- The storage key (which includes the normalized Supabase backend URL) is authenticated as GCM additional authenticated data, preventing ciphertext from being moved between backend entries.
- Preference writes use synchronous `commit()` inside `Dispatchers.IO` so the SessionManager does not report a successful save before persistence completes.
- The application context is initialized in `NuvioApplication.onCreate`, before activities or background workers can create the Supabase client.

### iOS

- Session JSON is stored as a Keychain generic-password item.
- The service is `com.nuvio.media.supabase.sessions`; the account is the backend-specific Supabase session key.
- Accessibility is `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: available after the first unlock following reboot, supporting background sync while remaining device-bound and excluded from migration to another device.
- No biometric access-control flag is used. Normal token refresh does not prompt the user.

## Migration and sign-out safety

The previous Supabase default session key is preserved exactly:

`sb-${supabaseUrl.removeSuffix("/").replace('/', '-').replace('.', '-')}-session`

The legacy storage locations are:

- Android: the `${applicationContext.packageName}_preferences` file used by multiplatform-settings-no-arg 1.3.0.
- iOS: `NSUserDefaults.standardUserDefaults`.

Migration is lazy, on the first session load:

1. Read native secure storage first.
2. Only if no native item exists, read the legacy session.
3. Decode the legacy session.
4. Write it to native secure storage.
5. Read the encrypted copy back and decode it.
6. Compare the restored session with the legacy session.
7. Delete the legacy plaintext value only after verification succeeds.

A failed write, decrypt, decode or verification never deletes the only legacy copy. An unreadable native item is not treated as missing by the Nuvio SessionManager and never falls back to an older plaintext copy. One SDK limitation remains: Supabase Kotlin 3.9.0's `AuthImpl.loadFromStorage` catches any thrown `Throwable`, logs it, and returns `false`; it does not expose the storage failure as a distinct session status. The encrypted/legacy data is preserved, but the SDK may temporarily behave as if no session was restored until a later load succeeds. A follow-up SDK-level change is needed to expose storage failures distinctly to the app.

Sign-out writes an encrypted tombstone before removing the legacy value. This prevents an interrupted legacy deletion from resurrecting an old refresh token on the next launch. A later successful sign-in overwrites the tombstone with the new session.

The serializer uses `Json { encodeDefaults = true }`, as required by the Supabase Kotlin SessionManager contract.

## Important upstream refresh issue — not yet fixed

Supabase Kotlin issue [#1506](https://github.com/supabase/supabase-kt/issues/1506) reports concurrent expired-token requests initiating multiple refresh calls and a race that can leave orphaned scheduled refresh jobs.

Supabase PR [#1509](https://github.com/supabase/supabase-kt/pull/1509) is still open and addresses orphaned scheduled jobs through atomic replacement. Its author explicitly states that it does **not** make simultaneous expired-token requests share one refresh call. The issue is reported against 3.9.0 as well.

This application change does not claim to solve that upstream race. Automatic refresh, lifecycle callbacks and Realtime remain enabled; disabling them would create separate session and Realtime correctness problems. A complete fix requires an upstream SDK change or a maintained SDK patch that implements both atomic job ownership and single-flight refresh. Do not represent the 3.9.0 upgrade or secure storage as resolving this concurrency bug.

## Verification status

- Source and upstream API contracts were inspected.
- Native Android and iOS implementations and common migration tests were added.
- No Gradle build, test task, Xcode build or GitHub Actions workflow was started, as requested.
- Android and iOS compilation, migration tests on both native targets, sign-in/refresh/sign-out integration, backend switching, and app-resume behavior remain to be verified in the next authorized build session.
