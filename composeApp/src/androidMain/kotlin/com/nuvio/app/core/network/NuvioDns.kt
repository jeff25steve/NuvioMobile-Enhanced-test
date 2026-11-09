package com.nuvio.app.core.network

import android.os.SystemClock
import android.util.Base64
import co.touchlab.kermit.Logger
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

private const val MAX_DNS_CACHE_TTL_SECONDS = 3_600L

internal object NuvioDns : Dns {
    private val log = Logger.withTag("NuvioDns")
    private val providerLock = Any()
    private val cache = DnsAddressCache()

    @Volatile
    var provider: DnsProvider = DnsProvider.SYSTEM
        set(value) {
            synchronized(providerLock) {
                if (field != value) {
                    field = value
                    cache.clear()
                }
            }
        }

    private data class ProviderSnapshot(
        val provider: DnsProvider,
        val cacheGeneration: Long,
    )

    private fun providerSnapshot(): ProviderSnapshot = synchronized(providerLock) {
        ProviderSnapshot(provider, cache.generation())
    }

    override fun lookup(hostname: String): List<InetAddress> {
        var snapshot = providerSnapshot()

        while (true) {
            val current = snapshot.provider
            val endpoint = DohEndpoint.forProvider(current)
            if (endpoint == null || hostname.isIpLiteral() || hostname.equals("localhost", ignoreCase = true)) {
                return Dns.SYSTEM.lookup(hostname)
            }

            val key = "${current.storageId}|${hostname.lowercase()}"
            val cached = synchronized(providerLock) {
                if (provider == current && cache.generation() == snapshot.cacheGeneration) {
                    cache.get(key, SystemClock.elapsedRealtime())
                } else {
                    null
                }
            }
            if (cached != null) return cached

            // A provider change between the initial snapshot and cache lookup must restart
            // the lookup against the newly selected provider.
            val latestSnapshot = providerSnapshot()
            if (latestSnapshot != snapshot) {
                snapshot = latestSnapshot
                continue
            }

            val answer = try {
                DohClient.resolve(endpoint, hostname)
            } catch (error: IOException) {
                log.w {
                    "DoH lookup via ${current.displayName} failed for $hostname, using system DNS: ${error.message}"
                }
                return Dns.SYSTEM.lookup(hostname)
            }
            if (answer.addresses.isEmpty()) {
                throw UnknownHostException(
                    "$hostname has no address (DNS over HTTPS via ${current.displayName})",
                )
            }

            answer.cacheExpiresAtMs?.let { expiresAtMs ->
                synchronized(providerLock) {
                    if (provider == current && cache.generation() == snapshot.cacheGeneration) {
                        cache.put(
                            key = key,
                            addresses = answer.addresses,
                            expiresAtMs = expiresAtMs,
                            nowMs = SystemClock.elapsedRealtime(),
                            expectedGeneration = snapshot.cacheGeneration,
                        )
                    }
                }
            }
            return answer.addresses
        }
    }

    private val ipv4Literal = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    private fun String.isIpLiteral(): Boolean = contains(':') || ipv4Literal.matches(this)
}

internal class DnsAddressCache(private val maxEntries: Int = 256) {
    private data class Entry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long,
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var generation = 0L

    init {
        require(maxEntries > 0) { "maxEntries must be greater than zero" }
    }

    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun get(key: String, nowMs: Long): List<InetAddress>? {
        val entry = entries[key] ?: return null
        if (entry.expiresAtMs <= nowMs) {
            entries.remove(key)
            return null
        }
        return entry.addresses
    }

    @Synchronized
    fun put(
        key: String,
        addresses: List<InetAddress>,
        expiresAtMs: Long,
        nowMs: Long,
    ): Boolean = put(key, addresses, expiresAtMs, nowMs, generation)

    @Synchronized
    fun put(
        key: String,
        addresses: List<InetAddress>,
        expiresAtMs: Long,
        nowMs: Long,
        expectedGeneration: Long,
    ): Boolean {
        if (expectedGeneration != generation || addresses.isEmpty() || expiresAtMs <= nowMs) {
            return false
        }

        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtMs <= nowMs) iterator.remove()
        }

        if (key !in entries && entries.size >= maxEntries) {
            val eldest = entries.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }

        entries[key] = Entry(addresses.toList(), expiresAtMs)
        return true
    }

    @Synchronized
    fun clear() {
        entries.clear()
        generation++
    }

    @Synchronized
    fun size(): Int = entries.size
}

internal class DohEndpoint private constructor(val host: String, val bootstrapAddresses: List<String>) {
    val url: String get() = "https://$host/dns-query"

    companion object {
        private val cloudflare = DohEndpoint("cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1"))
        private val google = DohEndpoint("dns.google", listOf("8.8.8.8", "8.8.4.4"))
        private val adguard = DohEndpoint("dns.adguard-dns.com", listOf("94.140.14.14", "94.140.15.15"))
        private val quad9 = DohEndpoint("dns.quad9.net", listOf("9.9.9.9", "149.112.112.112"))
        private val all = listOf(cloudflare, google, adguard, quad9)

        fun forProvider(provider: DnsProvider): DohEndpoint? = when (provider) {
            DnsProvider.SYSTEM -> null
            DnsProvider.CLOUDFLARE -> cloudflare
            DnsProvider.GOOGLE -> google
            DnsProvider.ADGUARD -> adguard
            DnsProvider.QUAD9 -> quad9
        }

        fun forHost(host: String): DohEndpoint? = all.firstOrNull { it.host.equals(host, ignoreCase = true) }
    }
}

internal data class DohAnswer(
    val addresses: List<InetAddress>,
    val ttlSeconds: Long?,
    val hasAnswerRecords: Boolean,
    val cacheExpiresAtMs: Long? = null,
)

internal data class DohResolution(
    val addresses: List<InetAddress>,
    val cacheExpiresAtMs: Long?,
)

internal fun remainingDnsTtlSeconds(ttlSeconds: Long?, ageHeader: String?): Long? {
    val ttl = ttlSeconds?.takeIf { it > 0L } ?: return null
    val age = when (ageHeader) {
        null -> 0L
        else -> {
            val ageValue = ageHeader.substringBefore(',').trim()
            if (ageValue.isEmpty() || ageValue.any { it !in '0'..'9' }) return null
            ageValue.toLongOrNull() ?: return null
        }
    }
    if (age >= ttl) return null
    return ttl - age
}

internal fun dnsCacheExpiryAtMs(
    ttlSeconds: Long?,
    ageHeader: String?,
    requestStartedAtMs: Long,
): Long? {
    val remainingTtl = remainingDnsTtlSeconds(ttlSeconds, ageHeader) ?: return null
    return requestStartedAtMs + remainingTtl.coerceAtMost(MAX_DNS_CACHE_TTL_SECONDS) * 1_000L
}

internal object DohClient {
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns { host ->
                DohEndpoint.forHost(host)?.bootstrapAddresses?.map { InetAddress.getByName(it) }
                    ?: Dns.SYSTEM.lookup(host)
            }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun resolve(endpoint: DohEndpoint, hostname: String): DohResolution {
        val v4 = query(endpoint, hostname, TYPE_A)
        val v6 = runCatching { query(endpoint, hostname, TYPE_AAAA) }.getOrNull()
        val addresses = v4.addresses + v6?.addresses.orEmpty()
        val answerBearingResponses = listOfNotNull(v4, v6).filter { it.hasAnswerRecords }
        val cacheExpiresAtMs = if (
            answerBearingResponses.isNotEmpty() &&
            answerBearingResponses.all { it.cacheExpiresAtMs != null }
        ) {
            answerBearingResponses.minOf { requireNotNull(it.cacheExpiresAtMs) }
        } else {
            null
        }
        return DohResolution(addresses, cacheExpiresAtMs)
    }

    private fun query(endpoint: DohEndpoint, hostname: String, type: Int): DohAnswer {
        val message = Base64.encodeToString(
            DnsMessage.query(hostname, type),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val request = Request.Builder()
            .url("${endpoint.url}?dns=$message")
            .header("Accept", "application/dns-message")
            .build()
        val requestStartedAtMs = SystemClock.elapsedRealtime()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${endpoint.host}")
            val body = response.body?.bytes() ?: throw IOException("Empty response from ${endpoint.host}")
            val parsed = DnsMessage.parseAnswer(hostname, body, type)
            return parsed.copy(
                cacheExpiresAtMs = if (parsed.hasAnswerRecords) {
                    dnsCacheExpiryAtMs(
                        ttlSeconds = parsed.ttlSeconds,
                        ageHeader = response.headers.values("Age").firstOrNull(),
                        requestStartedAtMs = requestStartedAtMs,
                    )
                } else {
                    null
                },
            )
        }
    }
}

internal object DnsMessage {
    private const val RCODE_NXDOMAIN = 3

    fun query(hostname: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        hostname.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Invalid host name: $hostname" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 1))
        return out.toByteArray()
    }

    fun parseAnswer(hostname: String, data: ByteArray, type: Int): DohAnswer {
        fun u8(at: Int): Int {
            if (at !in data.indices) throw IOException("Truncated DNS answer")
            return data[at].toInt() and 0xFF
        }
        fun u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
        fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        fun skipName(start: Int): Int {
            var at = start
            while (true) {
                val length = u8(at)
                when {
                    length == 0 -> return at + 1
                    length and 0xC0 == 0xC0 -> return at + 2
                    else -> at += length + 1
                }
            }
        }

        val rcode = u16(2) and 0x0F
        if (rcode == RCODE_NXDOMAIN) return DohAnswer(emptyList(), null, hasAnswerRecords = false)
        if (rcode != 0) throw IOException("DNS error code $rcode")
        val questions = u16(4)
        val answers = u16(6)
        var at = 12
        repeat(questions) { at = skipName(at) + 4 }
        val addresses = mutableListOf<InetAddress>()
        var ttlSeconds: Long? = null
        repeat(answers) {
            at = skipName(at)
            val recordType = u16(at)
            val recordTtl = u32(at + 4)
            val length = u16(at + 8)
            val dataStart = at + 10
            if (dataStart + length > data.size) throw IOException("Truncated DNS answer")

            // RFC 8484 requires the minimum TTL from the entire Answer section,
            // not only the queried A/AAAA RRset (for example, a shorter CNAME).
            ttlSeconds = ttlSeconds?.let { minOf(it, recordTtl) } ?: recordTtl

            val expected = if (type == 1) 4 else 16
            if (recordType == type && length == expected) {
                addresses += InetAddress.getByAddress(hostname, data.copyOfRange(dataStart, dataStart + length))
            }
            at = dataStart + length
        }
        return DohAnswer(addresses, ttlSeconds, hasAnswerRecords = answers > 0)
    }
}
