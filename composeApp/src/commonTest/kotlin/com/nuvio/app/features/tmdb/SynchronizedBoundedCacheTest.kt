package com.nuvio.app.features.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SynchronizedBoundedCacheTest {

    @Test
    fun `evicts the oldest inserted entry when capacity is exceeded`() {
        val cache = SynchronizedBoundedCache<String, String>(2)

        cache.put("first", "one")
        cache.put("second", "two")
        cache.put("third", "three")

        assertNull(cache.get("first"))
        assertEquals("two", cache.get("second"))
        assertEquals("three", cache.get("third"))
    }

    @Test
    fun `replacing a key does not refresh FIFO order or prefix lookup order`() {
        val cache = SynchronizedBoundedCache<String, String>(2)
        val firstKey = "123:movie:en"
        val secondKey = "123:movie:fr"

        cache.put(firstKey, "English")
        cache.put(secondKey, "French")
        assertEquals("English", cache.firstValueOrNull { it.startsWith("123:movie:") })

        cache.put(firstKey, "Updated English")
        assertEquals("Updated English", cache.firstValueOrNull { it.startsWith("123:movie:") })

        cache.put("456:movie:en", "Another title")
        assertEquals("French", cache.firstValueOrNull { it.startsWith("123:movie:") })
        assertNull(cache.get(firstKey))
    }

    @Test
    fun `concurrent reads and writes retain all keys within capacity`() = runTest {
        val cache = SynchronizedBoundedCache<Int, String>(16)

        coroutineScope {
            repeat(8) { worker ->
                launch(Dispatchers.Default) {
                    repeat(100) { pass ->
                        repeat(16) { key ->
                            cache.put(key, "$worker:$pass")
                            cache.get(key)
                        }
                    }
                }
            }
        }

        repeat(16) { key ->
            assertNotNull(cache.get(key))
        }
    }
}
