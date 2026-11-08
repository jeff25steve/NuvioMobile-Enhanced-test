package com.nuvio.app.core.concurrency

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A small thread-safe registry for replaceable callback/job handles.
 *
 * Identity-based removal prevents an older operation's completion callback from
 * deleting a newer value registered under the same key.
 */
internal class IdentityKeyedRegistry<K : Any, V : Any> {
    private val values = MutableStateFlow<Map<K, V>>(emptyMap())

    fun get(key: K): V? = values.value[key]

    fun replace(key: K, value: V): V? =
        values.getAndUpdate { current -> current + (key to value) }[key]

    fun remove(key: K): V? =
        values.getAndUpdate { current -> current - key }[key]

    fun removeIfSame(key: K, expected: V): Boolean {
        val previous = values.getAndUpdate { current ->
            if (current[key] === expected) current - key else current
        }
        return previous[key] === expected
    }
}
