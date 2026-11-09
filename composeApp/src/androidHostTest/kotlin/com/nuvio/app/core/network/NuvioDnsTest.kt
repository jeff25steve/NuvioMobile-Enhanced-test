package com.nuvio.app.core.network

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NuvioDnsTest {
    @Test
    fun answerTtlIncludesShorterLivedCnameRecord() {
        val answer = DnsMessage.parseAnswer(
            hostname = "example.com",
            data = dnsResponse(
                answerRecord(
                    type = 5,
                    ttlSeconds = 30L,
                    data = dnsName("alias", "example", "com"),
                ),
                answerRecord(
                    type = 1,
                    ttlSeconds = 120L,
                    data = byteArrayOf(203.toByte(), 0, 113, 7),
                    owner = dnsName("alias", "example", "com"),
                ),
            ),
            type = 1,
        )

        assertEquals(
            InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 7)),
            answer.addresses.single(),
        )
        assertEquals(30L, answer.ttlSeconds)
        assertTrue(answer.hasAnswerRecords)
    }

    @Test
    fun zeroTtlAddressRemainsAvailableButHasNoCacheLifetime() {
        val answer = DnsMessage.parseAnswer(
            hostname = "example.com",
            data = dnsResponse(
                answerRecord(
                    type = 1,
                    ttlSeconds = 0L,
                    data = byteArrayOf(203.toByte(), 0, 113, 7),
                ),
            ),
            type = 1,
        )

        assertEquals(1, answer.addresses.size)
        assertEquals(0L, answer.ttlSeconds)
        assertTrue(answer.hasAnswerRecords)
        assertNull(dnsCacheExpiryAtMs(answer.ttlSeconds, ageHeader = null, requestStartedAtMs = 1_000L))
    }

    @Test
    fun responseWithoutAnswerRecordsHasNoSyntheticTtl() {
        val answer = DnsMessage.parseAnswer(
            hostname = "example.com",
            data = dnsResponse(),
            type = 1,
        )

        assertTrue(answer.addresses.isEmpty())
        assertNull(answer.ttlSeconds)
        assertFalse(answer.hasAnswerRecords)
    }

    @Test
    fun absentAgeLeavesDnsTtlUnchanged() {
        assertEquals(120L, remainingDnsTtlSeconds(ttlSeconds = 120L, ageHeader = null))
    }

    @Test
    fun ageIsSubtractedFromDnsTtlAndUsesFirstListMember() {
        assertEquals(40L, remainingDnsTtlSeconds(ttlSeconds = 120L, ageHeader = "80"))
        assertEquals(40L, remainingDnsTtlSeconds(ttlSeconds = 120L, ageHeader = "80, 100"))
    }

    @Test
    fun requestStartAnchorsExpirySoResponseTransitTimeCannotExtendDnsLifetime() {
        assertEquals(
            41_000L,
            dnsCacheExpiryAtMs(ttlSeconds = 120L, ageHeader = "80", requestStartedAtMs = 1_000L),
        )
    }

    @Test
    fun exhaustedZeroOrMalformedAgesAreNotCacheable() {
        assertNull(remainingDnsTtlSeconds(ttlSeconds = 120L, ageHeader = "120"))
        assertNull(remainingDnsTtlSeconds(ttlSeconds = 120L, ageHeader = "121"))
        assertNull(remainingDnsTtlSeconds(ttlSeconds = 0L, ageHeader = null))
        assertNull(remainingDnsTtlSeconds(ttlSeconds = null, ageHeader = null))
        assertNull(remainingDnsTtlSeconds(ttlSeconds = 120L, ageHeader = "invalid"))
        assertNull(remainingDnsTtlSeconds(ttlSeconds = 120L, ageHeader = "-1"))
        assertNull(remainingDnsTtlSeconds(ttlSeconds = 120L, ageHeader = ""))
    }

    @Test
    fun cacheExpiresEntryAtExactExpiryBoundary() {
        val cache = DnsAddressCache(maxEntries = 2)
        val address = address(1)

        assertTrue(cache.put("a", listOf(address), expiresAtMs = 10L, nowMs = 0L))
        assertEquals(listOf(address), cache.get("a", nowMs = 9L))
        assertNull(cache.get("a", nowMs = 10L))
        assertEquals(0, cache.size())
    }

    @Test
    fun cachePrunesExpiredEntriesBeforeInsertion() {
        val cache = DnsAddressCache(maxEntries = 2)
        val first = address(1)
        val second = address(2)
        val third = address(3)

        assertTrue(cache.put("expired", listOf(first), expiresAtMs = 5L, nowMs = 0L))
        assertTrue(cache.put("live", listOf(second), expiresAtMs = 100L, nowMs = 0L))
        assertTrue(cache.put("new", listOf(third), expiresAtMs = 100L, nowMs = 10L))

        assertEquals(2, cache.size())
        assertNull(cache.get("expired", nowMs = 10L))
        assertEquals(listOf(second), cache.get("live", nowMs = 10L))
        assertEquals(listOf(third), cache.get("new", nowMs = 10L))
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedEntryAtCapacity() {
        val cache = DnsAddressCache(maxEntries = 2)
        val first = address(1)
        val second = address(2)
        val third = address(3)

        assertTrue(cache.put("first", listOf(first), expiresAtMs = 100L, nowMs = 0L))
        assertTrue(cache.put("second", listOf(second), expiresAtMs = 100L, nowMs = 0L))
        assertEquals(listOf(first), cache.get("first", nowMs = 1L))
        assertTrue(cache.put("third", listOf(third), expiresAtMs = 100L, nowMs = 2L))

        assertEquals(2, cache.size())
        assertEquals(listOf(first), cache.get("first", nowMs = 3L))
        assertNull(cache.get("second", nowMs = 3L))
        assertEquals(listOf(third), cache.get("third", nowMs = 3L))
    }

    @Test
    fun cacheClearInvalidatesInFlightInsertionFromPreviousGeneration() {
        val cache = DnsAddressCache(maxEntries = 2)
        val oldGeneration = cache.generation()
        val address = address(1)

        assertTrue(cache.put("before", listOf(address), expiresAtMs = 100L, nowMs = 0L, expectedGeneration = oldGeneration))
        cache.clear()

        assertFalse(
            cache.put(
                "late",
                listOf(address),
                expiresAtMs = 100L,
                nowMs = 1L,
                expectedGeneration = oldGeneration,
            ),
        )
        assertEquals(0, cache.size())
        assertNull(cache.get("late", nowMs = 1L))
        assertEquals(oldGeneration + 1L, cache.generation())
    }

    private fun address(lastOctet: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, lastOctet.toByte()))
}

private fun dnsResponse(vararg answerRecords: ByteArray): ByteArray {
    val query = DnsMessage.query("example.com", 1)
    val header = byteArrayOf(
        0,
        0,
        0x81.toByte(),
        0x80.toByte(),
        0,
        1,
        (answerRecords.size shr 8).toByte(),
        answerRecords.size.toByte(),
        0,
        0,
        0,
        0,
    )
    val question = query.copyOfRange(12, query.size)
    val answers = answerRecords.fold(ByteArray(0)) { combined, record -> combined + record }
    return header + question + answers
}

private fun dnsName(vararg labels: String): ByteArray =
    labels.fold(ByteArray(0)) { result, label ->
        val bytes = label.encodeToByteArray()
        result + byteArrayOf(bytes.size.toByte()) + bytes
    } + byteArrayOf(0)

private fun answerRecord(
    type: Int,
    ttlSeconds: Long,
    data: ByteArray,
    owner: ByteArray = byteArrayOf(0xC0.toByte(), 0x0C),
): ByteArray {
    val length = data.size
    return owner + byteArrayOf(
        (type shr 8).toByte(),
        type.toByte(),
        0,
        1,
        (ttlSeconds ushr 24).toByte(),
        (ttlSeconds ushr 16).toByte(),
        (ttlSeconds ushr 8).toByte(),
        ttlSeconds.toByte(),
        (length shr 8).toByte(),
        length.toByte(),
    ) + data
}
