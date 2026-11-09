# Android DoH Cache Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make the Android DoH resolver honour DNS record lifetimes and HTTP Age while bounding cache memory and preserving concurrent/provider-switch correctness.

**Architecture:** Keep resolution synchronous through the existing OkHttp Dns API. Parse the minimum TTL across the DNS Answer section, subtract HTTP Age, and treat zero/invalid/expired lifetimes as non-cacheable while still returning current-query addresses. Replace the unbounded map with a small synchronized access-ordered cache; keep all DoH network I/O outside locks and use a provider generation so an old in-flight response cannot repopulate a cache invalidated by a provider change.

**Tech Stack:** Kotlin 2.4.10, Android source set, OkHttp 4.12.0, Android SystemClock.elapsedRealtime(), JUnit/Kotlin Test in androidHostTest.

**Spec:** docs/engineering/codebase-improvement-notes.md — “Android custom DNS — honour answer lifetimes and bound resolver cache”.

## Global Constraints

- Android-only implementation; do not change iOS DNS behavior or the common DNS settings API.
- Keep System, Cloudflare, Google, AdGuard and Quad9 provider selection and the existing system-DNS fallback.
- Preserve A-before-AAAA resolution order and the current optional AAAA failure behavior.
- Keep the 3,600-second upper TTL cap; remove the unsafe 60-second lower floor.
- Do not add dependencies, persistent cache state, periodic cleanup jobs, request single-flight or parallel A/AAAA queries.
- Never hold the cache/provider lock during network I/O.
- Keep cache keys provider-specific and case-insensitive by hostname.

## Review Focus

- A CNAME or other Answer record has a shorter TTL than the A/AAAA address record.
- DNS TTL is zero, HTTP Age equals/exceeds the DNS TTL, or HTTP Age is malformed.
- A cached entry expires while its hostname is not queried again.
- Many unique hostnames exceed the retained-entry capacity.
- Concurrent OkHttp lookups and a provider change overlap an in-flight DoH response.

---

### Task 1: DNS Answer TTL parsing

**Files:**
- Modify: composeApp/src/androidMain/kotlin/com/nuvio/app/core/network/NuvioDns.kt
- Test: composeApp/src/androidHostTest/kotlin/com/nuvio/app/core/network/NuvioDnsTest.kt

**Interfaces:**
- Preserve DnsMessage.parseAnswer(hostname: String, data: ByteArray, type: Int): DohAnswer.
- DohAnswer will expose parsed address records, nullable/raw answer TTL and whether the response Answer section contained records.

- [ ] **Step 1: Add a wire-format regression test** named answerTtlIncludesShorterLivedCnameRecord. Build a literal DNS response containing CNAME TTL 30 and A TTL 120; assert one returned IPv4 address and an Answer TTL of exactly 30 seconds.
- [ ] **Step 2: Run the Android host-test task** and confirm the new test fails because the parser currently only considers the queried A/AAAA type.
- [ ] **Step 3: Update the parser** to track the minimum TTL across all records in the Answer section, while still extracting only the queried address type. Represent no Answer-section TTL as absent instead of inventing 60 seconds. Preserve zero TTL in the parsed result.
- [ ] **Step 4: Add/verify a zero-TTL parser case** asserting the address remains available to the current lookup and its parsed TTL remains zero.

### Task 2: HTTP Age and non-cacheable lifetimes

**Files:**
- Modify: composeApp/src/androidMain/kotlin/com/nuvio/app/core/network/NuvioDns.kt
- Test: composeApp/src/androidHostTest/kotlin/com/nuvio/app/core/network/NuvioDnsTest.kt

**Interfaces:**
- Add remainingDnsTtlSeconds(ttlSeconds: Long?, ageHeader: String?): Long?.
- The helper returns null for absent/zero TTL, invalid or negative Age, or Age greater than/equal to TTL; otherwise it returns TTL minus Age. Missing Age means zero.

- [ ] **Step 1: Add tests first** for absent Age, 120 - 80 = 40, Age equal to TTL, Age greater than TTL, zero TTL, malformed Age and negative Age.
- [ ] **Step 2: Run the Android host-test task** and confirm the new helper tests fail because the helper is not yet implemented.
- [ ] **Step 3: Implement the pure TTL helper** and apply it to each successful DoH HTTP response using the response's Age header.
- [ ] **Step 4: Combine A/AAAA cache lifetimes** using the minimum lifetime across every answer-bearing response, including CNAME-only responses; if any relevant response is non-cacheable, do not cache the combined address list.

### Task 3: Bounded concurrent DNS cache and provider invalidation

**Files:**
- Modify: composeApp/src/androidMain/kotlin/com/nuvio/app/core/network/NuvioDns.kt
- Test: composeApp/src/androidHostTest/kotlin/com/nuvio/app/core/network/NuvioDnsTest.kt

**Interfaces:**
- Add an internal DnsAddressCache(maxEntries: Int = 256) with synchronized get(key, nowMs), put(key, addresses, expiresAtMs, nowMs) and clear() operations.
- Cache reads remove an expired key; writes prune expired entries and evict least-recently-used entries when capacity is reached.

- [ ] **Step 1: Add tests first** for expiry-on-read, expiry pruning on insertion, access-order LRU eviction, hard capacity and clear.
- [ ] **Step 2: Run the Android host-test task** and confirm the tests fail because the bounded cache is not yet implemented.
- [ ] **Step 3: Implement the bounded cache** with a synchronized access-ordered LinkedHashMap; keep the capacity constructor-injectable for small deterministic tests.
- [ ] **Step 4: Integrate the cache** into NuvioDns.lookup, use elapsedRealtime() after the response arrives, clamp only the upper TTL bound, and skip insertion when no positive TTL remains.
- [ ] **Step 5: Guard provider changes** with a generation snapshot. Cache hits and inserts must be linearized against provider updates; a response from an older provider generation must never repopulate the cleared cache. Keep DoH requests outside both locks.

### Final verification

- [ ] Run the complete :composeApp:testAndroidHostTest task for the full Android distribution.
- [ ] Run the repository's Linux fullDebug APK workflow and the iOS Debug IPA workflow to ensure the shared project remains buildable; the production edit itself must remain Android-only.
- [ ] Inspect the complete Git diff and confirm only the planned DNS source, DNS test, plan and engineering notes changed on main-build-testing. Keep validation workflow changes isolated to the dedicated CI validation branch.
- [ ] Record actual test/build results, source references, any rejected alternatives and remaining uncertainty in docs/engineering/codebase-improvement-notes.md.
