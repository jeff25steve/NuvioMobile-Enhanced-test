package com.nuvio.app.core.ui

internal data class DuplicateSafeLazyEntry<T>(
    val value: T,
    val lazyKey: Any,
)

internal class DuplicateSafeLazyKeysCache<T>(
    private val key: (T) -> Any,
) {
    private var source: List<T>? = null
    private var entries: List<DuplicateSafeLazyEntry<T>> = emptyList()

    fun getOrCreate(items: List<T>): List<DuplicateSafeLazyEntry<T>> {
        if (source === items) return entries

        return items.withDuplicateSafeLazyKeys(key).also { derived ->
            source = items
            entries = derived
        }
    }
}

internal fun <T> List<T>.withDuplicateSafeLazyKeys(key: (T) -> Any): List<DuplicateSafeLazyEntry<T>> {
    val keyCounts = groupingBy(key).eachCount()
    val occurrences = mutableMapOf<Any, Int>()

    return map { entry ->
        val baseKey = key(entry)
        val lazyKey = if (keyCounts[baseKey] == 1) {
            baseKey
        } else {
            val occurrence = occurrences.getOrElse(baseKey) { 0 }
            occurrences[baseKey] = occurrence + 1
            "$baseKey#$occurrence"
        }
        DuplicateSafeLazyEntry(value = entry, lazyKey = lazyKey)
    }
}
