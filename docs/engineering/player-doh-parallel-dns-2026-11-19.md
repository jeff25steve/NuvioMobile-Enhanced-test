# Android DoH lookup parallelization — 19 November 2026

## Summary

The Android custom DNS-over-HTTPS path previously resolved A and AAAA records sequentially. A slow IPv4 response delayed the start of the independent IPv6 request, extending the DNS portion of a first connection. The resolver now starts the AAAA request asynchronously before performing the required A lookup.

This applies only when the user has selected a custom DoH provider. The default system DNS path is unchanged.

## Implementation

- Reused the existing DoH OkHttp client and its connection pool.
- Queued the AAAA query with `Call.enqueue(Callback)`, then performed the A query.
- Waited for the optional AAAA result so IPv6 connectivity remains available, while overlapping the two network requests.
- Kept A lookup failures authoritative; an outstanding AAAA call is cancelled when A fails.
- Kept AAAA failures optional, preserving valid A records.
- Closed async responses with `Response.use`, made completion idempotent, and restored interruption after cancelling the AAAA call.
- Kept existing answer parsing, request-start TTL accounting, minimum TTL cache expiry, DNS cache, provider-change invalidation and IPv4-first address ordering.

The expected reduction is from serial A+AAAA network latency to overlapping requests. This is a critical-path reduction, not a measured startup improvement; actual impact depends on the selected DNS provider, network and cache state.

## Regression coverage added (not executed)

Added three host-test contracts in `composeApp/src/androidHostTest/kotlin/com/nuvio/app/core/network/NuvioDnsTest.kt`:

1. AAAA is started before the blocking A query.
2. An AAAA failure does not discard a valid A answer or its cache expiry.
3. An A failure cancels the outstanding AAAA call and preserves the original exception.

The test source was inspected, but tests were not executed.

## Upgrade check

The repository version catalog at this checkpoint pins:

- Ktor: `3.4.1`
- AndroidX Media3: `1.8.0`

The current official release pages show Ktor `3.6.1` (30 October 2026) and Media3 `1.11.1` (10 September 2026) as stable. Media3 `1.12.0-beta01` is not recommended as a production dependency in this pass.

Relevant changes worth reviewing separately:

- Media3 1.10.0 fixes a `ProgressiveMediaSource` timeline issue that could remove queued periods. Media3 1.11.0 fixes a timing-dependent ExoPlayer concurrency issue. Media3 1.11.1 includes further HLS and renderer fixes and moves HAGC parsing configuration from `MediaSource.Factory` to `ExtractorsFactory`. This API movement makes a controlled upgrade and source review necessary.
- Ktor 3.6.1 includes an OkHttp-engine response-reading dispatch/allocation improvement and a fix for `HttpStatement.execute` when a block abandons an unread or partially-read streaming body.
- Ktor 3.4.1's published OkHttp engine dependency resolves OkHttp 5.3.2. Do not independently pin OkHttp without first reviewing Ktor's dependency alignment and the full resolved graph.

No dependency versions were changed. Upgrade review links:

- [AndroidX Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3)
- [Ktor releases](https://ktor.io/docs/releases.html)
- [Ktor 3.6 changelog](https://ktor.io/changelog/3.6/)
- [Ktor 3.4.1 OkHttp engine artifact](https://central.sonatype.com/artifact/io.ktor/ktor-client-okhttp-jvm/3.4.1)

The Context7 connection in this session exposed library resolution and documentation queries, but did not expose a distinct Upgrade Guides operation. The available versioned documentation lookups were supplemented with the official release notes above; no claim is made that a separate Upgrade Guides report ran.

## Evidence and verification boundary

- The concurrency pattern follows Square's OkHttp 5.3.2 DoH implementation, which queues independent A and AAAA requests and awaits their completion: [OkHttp 5.3.2 DnsOverHttps source](https://github.com/square/okhttp/blob/parent-5.3.2/okhttp-dnsoverhttps/src/main/kotlin/okhttp3/dnsoverhttps/DnsOverHttps.kt).
- OkHttp's connection documentation describes connection-pool reuse and route selection: [OkHttp connections](https://github.com/square/okhttp/blob/master/docs/features/connections.md).
- No Gradle tasks, tests, builds, APK/AAB packaging, benchmark, CI or device playback were run. The implementation and test source received static review only; runtime, compilation and latency remain unverified.
