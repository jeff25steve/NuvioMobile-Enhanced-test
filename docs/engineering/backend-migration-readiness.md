# Nuvio Enhanced backend migration and trust readiness

Date: 12 November 2026
Branch reviewed: `main-build-testing`

## Executive finding

The reported early-2027 credential rotation is **not officially confirmed and is now materially contradicted by additional contemporaneous evidence**. [Nuvio Enhanced issue #371](https://github.com/luqmanfadlli/NuvioMobile-Enhanced/issues/371) was opened on 12 November 2026 as an unanswered feature question. It attributes the claim to the Italian community but links no original announcement, maintainer post, release note or policy. It had no maintainer response when checked.

The user then supplied a screenshot from the Nuvio Discord `#general-chat` conversation on 12 November 2026. A sender displayed as `skoruppa` with a `NUVIO` role badge says, conditionally, that **if** forks do something bad, they would assume the team could issue API keys per developer to identify and limit/block a problematic app or fork. In the same conversation the sender says they had not heard this from Tap and expected Tap to announce such a policy in the official Discord. This is not an authenticated official policy statement, and the screenshot alone cannot verify the sender's employment/authority or Tap's private plans. However, it is strong counter-evidence to the rumour of a blanket early-2027 shutdown. The key-per-developer idea is explicitly speculative ("I would assume"), not a confirmed implementation or schedule.

**Updated risk posture:** do not plan around a blanket shutdown date. Retain a contingency for targeted key revocation or rate-limiting if a particular fork causes abuse, and keep the self-host migration route as a fallback. No user migration, public community backend or automatic endpoint change should be triggered by this rumour.

The official [Nuvio self-host repository](https://github.com/NuvioMedia/self-host) is real and documents a self-hosted Supabase deployment, a client discovery protocol and a server-side account importer. Its documented publishable key is public client configuration, not a service-role secret. Its documentation describes personal self-hosting and discourages public third-party hosted backends. This provides a credible fallback path without making a community-operated backend the default.

## How to interpret the Discord developer discussion

The screenshot is best treated as **first-hand but informal evidence**:

- It shows a Discord conversation, not a published policy, changelog or maintainer announcement.
- The sender's `NUVIO` role badge is visible in the image; the sender's actual role and authority have not been independently authenticated in this review.
- The statement about developer-specific API keys is conditional and speculative. It describes a potential targeted control if a fork causes abuse; it does not state that all forks will be blocked or that credentials will rotate in early 2027.
- The sender explicitly says they had not heard this from Tap and expected an official announcement in the project Discord if such a decision had been made.
- The thread also includes another participant questioning the source and saying they had heard nothing from Tap. This reinforces that the original report was circulating as hearsay, not as an acknowledged decision.

### Three different mechanisms must not be conflated

| Mechanism | Possible effect | Current evidence |
|---|---|---|
| One shared upstream publishable key is rotated | Every client still using the old key may lose access until updated | Hypothetical risk in issue #371; no official date or plan found |
| Per-developer/per-fork public client keys are issued and selectively revoked | The upstream could distinguish clients by key and target a particular key | Mentioned as a developer's assumption in the screenshot; no confirmed product design |
| Backend actively blocks unofficial clients regardless of key | Would require additional server-side enforcement or identity signals | No official announcement or implementation evidence found |

A Supabase publishable/anon key is designed to be public client configuration. It can identify a project/client configuration for routing, analytics or selective rate limiting, but it is **not a cryptographic attestation that a request came from an untampered official APK**. It can be extracted and copied from an application. Any restriction must be enforced server-side, and a per-fork key alone should not be treated as a trustworthy device or binary identity. Do not embed service-role keys or administrative secrets as a supposed workaround.

The official self-host client guide says each self-hosted deployment publishes its own backend URL and publishable key at `<BACKEND_URL>/.well-known/nuvio`. A user's independent deployment therefore has its own key and does not rely on the official hosted project's publishable key. The origin-binding hardening in this branch follows that documented discovery model.

### Practical readiness decision

1. Keep the official Nuvio backend as the default while it remains available.
2. Keep optional user-controlled self-hosting on the Full build as the migration escape hatch; do not silently move users or their credentials.
3. If upstream later offers legitimate per-developer client keys, evaluate the official process and terms before adopting one. Do not attempt to extract, copy, spoof or bypass another app's key.
4. If upstream announces a targeted restriction, determine whether this fork is affected from the maintainer's explicit criteria and a controlled compatibility test. Do not infer a global ban from an API error alone.
5. If an official blanket policy is ever confirmed, use the existing self-host importer/discovery route and a documented account-migration plan. Keep backups and validate imported profiles, libraries, add-ons and watch progress before deleting the source account.

## What this fork already supports

The reviewed branch already has runtime backend selection; a second custom-URL subsystem is unnecessary.

- Full Android and iOS build policies enable custom server connections. Play Store and iOS App Store policies deliberately disable them.
- Users of an enabled Full build can enter a server URL; the client fetches `/.well-known/nuvio`, validates a Nuvio self-host discovery document, displays the backend and a trust confirmation, and persists the URL plus publishable key.
- Supabase Auth, PostgREST, Functions, Storage and Realtime use the selected configuration. Switching back to the official service is supported.
- The discovered publishable key is public client configuration, not a service-role/admin key. Never place a Supabase service-role key, database password, JWT signing secret or other server secret in the mobile app.

The official importer is a **server-side** operation: `./nuvio import-account`. Its documentation says it can transfer profiles, add-ons/plugins, library data, watch progress, settings and collections. It is not an in-app automatic migration; users must reconnect to the new server and re-establish external services and security factors. Keep the original account and deployment available until the import has been checked.

## Alternatives considered

| Approach | Benefit | Risk / decision |
|---|---|---|
| Keep the official service as default; retain custom discovery as an opt-in fallback | No change for existing users; already supported on Full builds | Recommended now. Do not switch users based on an unverified date. |
| User self-hosts the official Nuvio stack and imports their account on that server | Uses the official migration path and gives the user control of data | Recommended fallback. Requires server operation, backups, HTTPS and maintenance. |
| Run a public/community sync backend for Enhanced users | Central and convenient | Not recommended now. It creates a high-value custodian of passwords, refresh tokens, profiles, viewing history and personal data, plus hosting, abuse, backup, privacy and incident-response obligations. Upstream self-host docs discourage third-party public backend operators. |
| Build a separate Enhanced cloud sync service | Independent of upstream Supabase credentials | Largest option: new auth/sync backend, account migration, data model compatibility, operations and security review. Not justified until upstream policy is confirmed and the official importer/API proves insufficient. |
| Change only the embedded publishable key at build time | Simple release-time update | Not a durable migration plan: publishable keys are intentionally delivered to clients and the official discovery endpoint publishes client configuration. A fork restriction would need server-side policy; a client key change alone does not enforce binary authenticity. |

## Security improvements integrated in this cycle

1. **Discovery redirect and origin binding.** Discovery previously followed redirects and accepted a `backend_url` from the JSON without requiring it to share the entered discovery origin. The request now disables redirects on Android and iOS, verifies the response origin, and rejects an advertised backend URL if scheme, host or effective port differs. Reverse-proxy path prefixes on the same origin remain valid. The URL the user entered is now the trust anchor; an unrelated origin cannot be substituted by a discovery document.
2. **Backend switching and local-data isolation.** Switching previously cleared the anonymous ID and remote Supabase session but did not run the full local account cleanup used by normal sign-out. It now runs that cleanup only after both credential clears succeed. A cleanup failure aborts the backend change; cancellation propagates. The active SDK user ID is captured before clearing credentials so downloads and account-bound device credentials can be cleaned against the prior identity.
3. **Explicit destructive-switch warning.** Both custom-server and return-to-official confirmations disclose that account-specific device data, including downloaded files and local sync caches, is cleared and not migrated. Data on the old server remains there. Do not delete the old account/server until migration is verified.
4. **No embedded administrator secrets.** Only the public publishable key is stored as client configuration. Importer and server credentials remain on the trusted self-host machine.

The client still permits privately hosted HTTP endpoints, with its existing warning, to preserve local-network use cases. Do not enter account credentials or use account sync over public HTTP; use HTTPS with a trusted certificate for remote deployments.

## High-priority security follow-up: Supabase refresh-token storage

Supabase Kotlin **3.4.1** source confirms `AuthConfigDefaults.sessionManager` defaults to `SettingsSessionManager`. Its 3.4.1 implementation serializes the complete `UserSession` through multiplatform Settings. Android/iOS default platform settings are SharedPreferences/NSUserDefaults, not an app-owned Android Keystore/iOS Keychain-backed encrypted session store. The current fork installs Auth without a custom session manager.

This is a separate P1 hardening task before broad distribution of a security-sensitive build. Implement a platform-backed `SessionManager` (Android Keystore-protected encryption; iOS Keychain with device-only accessibility), preserve Supabase's required `encodeDefaults = true` serialization semantics, and design a one-time migration that verifies secure persistence before deleting legacy sessions. Test sign-in, refresh, process death, backend switch, sign-out, cancellation, key invalidation and recovery on both platforms. Do not improvise a storage format or silently discard existing sessions.

## Remaining readiness checks

- No longer treat early 2027 as a working deadline. Re-open this question only if Tap or another authorised upstream channel publishes a specific policy or the service begins returning a documented client-key restriction.
- Compare self-host capabilities with this fork's actual feature usage (not just login). Verify TV login/device-link, profile, library, settings, watch-progress and other sync behaviour against a running self-host instance.
- Validate `./nuvio import-account` on a disposable self-host instance; record imported data and what needs reconnecting; keep an independent backup.
- Decide whether non-Full distribution variants should expose custom-server selection. They currently disable it intentionally; changing that is a release-policy decision, not a backend code fix.
- Complete platform-secure session storage before claiming account credentials are protected at rest.
- Do not launch a public community backend until ownership, access control, backups/restore, abuse prevention, privacy/legal terms, incident response, funding and operator succession are agreed.

## Validation status

- Reviewed the GitHub issue, official self-host documentation/repository, actual client discovery and Supabase switch code, and exact Ktor 3.4.1 / Supabase Kotlin 3.4.1 source.
- Added regression tests for origin binding and server-switch cleanup ordering, failure and cancellation. They have **not** been run.
- No Gradle tests, builds, benchmark tasks, workflow dispatches or CI were run. This is intentional per the user's instruction.
- The commit uses `[skip ci]`; no manual workflow was dispatched.

## References

- [Unanswered Enhanced issue #371](https://github.com/luqmanfadlli/NuvioMobile-Enhanced/issues/371)
- User-supplied Nuvio Discord `#general-chat` screenshot, 12 November 2026 (informal developer discussion; no public message URL was supplied)
- [Official Nuvio self-host repository](https://github.com/NuvioMedia/self-host)
- [Official client configuration](https://github.com/NuvioMedia/self-host/blob/main/docs/client-configuration.md)
- [Official self-hosting guide](https://github.com/NuvioMedia/self-host/blob/main/docs/self-hosting.md)
- [Official account importer](https://github.com/NuvioMedia/self-host/blob/main/scripts/import-account.sh)
- [Ktor 3.4.1 Url source](https://github.com/ktorio/ktor/blob/3.4.1/ktor-http/common/src/io/ktor/http/Url.kt)
- [Supabase Kotlin 3.4.1 AuthConfig source](https://github.com/supabase/supabase-kt/blob/3.4.1/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/AuthConfig.kt)
- [Supabase Kotlin 3.4.1 SettingsSessionManager source](https://github.com/supabase/supabase-kt/blob/3.4.1/Auth/src/settingsMain/kotlin/io/github/jan/supabase/auth/SettingsSessionManager.kt)
- [OWASP Mobile Application Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Mobile_Application_Security_Cheat_Sheet.html)
