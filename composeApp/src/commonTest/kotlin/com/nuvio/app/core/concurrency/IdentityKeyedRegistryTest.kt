package com.nuvio.app.core.concurrency

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IdentityKeyedRegistryTest {

    @Test
    fun `stale completion cannot remove a replacement value`() {
        val registry = IdentityKeyedRegistry<String, Job>()
        val first = Job()
        val replacement = Job()

        assertNull(registry.replace("download-1", first))
        assertSame(first, registry.replace("download-1", replacement))

        assertFalse(registry.removeIfSame("download-1", first))
        assertSame(replacement, registry.get("download-1"))
        assertTrue(registry.removeIfSame("download-1", replacement))
        assertNull(registry.get("download-1"))
    }

    @Test
    fun `removing one key leaves other registered values intact`() {
        val registry = IdentityKeyedRegistry<String, Job>()
        val first = Job()
        val second = Job()

        registry.replace("first", first)
        registry.replace("second", second)

        assertSame(first, registry.remove("first"))
        assertNull(registry.get("first"))
        assertSame(second, registry.get("second"))
    }
}
