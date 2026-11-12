# Nuvio Enhanced backend migration and trust readiness

Date: 12 November 2026
Branch reviewed: `main-build-testing`

## Executive finding

The reported early-2027 credential rotation is **not officially confirmed by the evidence found**. [Nuvio Enhanced issue #371](https://github.com/luqmanfadlli/NuvioMobile-Enhanced/issues/371) was opened on 12 November 2026 as an unanswered feature question. It attributes the claim to the Italian community but links no original announcement, maintainer post, release note or policy. It had no maintainer response when checked. Treat the date as an unverified risk signal, not a fact.

The official [Nuvio self-host repository](https://github.com/NuvioMedia/self-host) is real and documents a self-hosted Supabase deployment, a client discovery protocol and a server-side account importer. Its documentation describes personal self-hosting and discourages public third-party hosted backends. This provides a credible fallback path without making a community-operated backend the default.

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

- Obtain the original community announcement or an official maintainer statement before treating early 2027 as a deadline.
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
- [Official Nuvio self-host repository](https://github.com/NuvioMedia/self-host)
- [Official client configuration](https://github.com/NuvioMedia/self-host/blob/main/docs/client-configuration.md)
- [Official self-hosting guide](https://github.com/NuvioMedia/self-host/blob/main/docs/self-hosting.md)
- [Official account importer](https://github.com/NuvioMedia/self-host/blob/main/scripts/import-account.sh)
- [Ktor 3.4.1 Url source](https://github.com/ktorio/ktor/blob/3.4.1/ktor-http/common/src/io/ktor/http/Url.kt)
- [Supabase Kotlin 3.4.1 AuthConfig source](https://github.com/supabase/supabase-kt/blob/3.4.1/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/AuthConfig.kt)
- [Supabase Kotlin 3.4.1 SettingsSessionManager source](https://github.com/supabase/supabase-kt/blob/3.4.1/Auth/src/settingsMain/kotlin/io/github/jan/supabase/auth/SettingsSessionManager.kt)
- [OWASP Mobile Application Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Mobile_Application_Security_Cheat_Sheet.html)
