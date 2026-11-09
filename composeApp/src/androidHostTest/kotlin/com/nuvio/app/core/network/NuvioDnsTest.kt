package com.nuvio.app.core.network

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

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

        assertEquals(InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 7)), answer.addresses.single())
        assertEquals(30L, answer.ttlSeconds)
    }
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
