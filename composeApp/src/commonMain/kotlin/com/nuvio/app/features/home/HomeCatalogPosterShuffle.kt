package com.nuvio.app.features.home

import kotlin.random.Random

/**
 * One seed per process. It is intentionally not persisted: a fresh app process gets a new order,
 * while backgrounding/resuming or recomposing the Home screen never changes the order.
 */
internal object HomeCatalogPosterShuffleSession {
    val seed: Long by lazy { Random.Default.nextLong() }
}

private const val FNV_OFFSET_BASIS = -3750763034362895579L
private const val FNV_PRIME = 1099511628211L
private const val AVALANCHE_MULTIPLIER_1 = -4658895280553007687L
private const val AVALANCHE_MULTIPLIER_2 = -7723592293110705685L

private data class RankedCatalogPoster<T>(
    val item: T,
    val key: String,
    val rank: Long,
)

/**
 * Orders the supplied homepage preview without losing, duplicating, or mutating poster objects.
 *
 * A poster's rank depends on the app-process seed, its catalog key, and its stable media key.
 * This means the same poster keeps its relative position when a catalog receives more items during
 * progressive loading, while different catalogs receive independent permutations.
 */
internal fun <T> orderCatalogPosters(
    items: List<T>,
    catalogKey: String,
    sessionSeed: Long,
    enabled: Boolean,
    stableKey: (T) -> String,
): List<T> {
    if (!enabled || items.size < 2) return items

    return items
        .map { item ->
            val key = stableKey(item)
            RankedCatalogPoster(
                item = item,
                key = key,
                rank = stableCatalogPosterRank(
                    sessionSeed = sessionSeed,
                    catalogKey = catalogKey,
                    posterKey = key,
                ),
            )
        }
        .sortedWith(
            compareBy<RankedCatalogPoster<T>>({ it.rank }, { it.key }),
        )
        .map { rankedPoster -> rankedPoster.item }
}

/**
 * Stable 64-bit FNV-1a followed by a SplitMix-style avalanche. All arithmetic is Kotlin Long
 * arithmetic in commonMain; overflow is intentional and supplies the hash's wraparound behavior.
 */
internal fun stableCatalogPosterRank(
    sessionSeed: Long,
    catalogKey: String,
    posterKey: String,
): Long {
    var hash = FNV_OFFSET_BASIS xor sessionSeed
    for (character in catalogKey) {
        hash = (hash xor character.code.toLong()) * FNV_PRIME
    }
    hash = (hash xor 0xFFL) * FNV_PRIME
    for (character in posterKey) {
        hash = (hash xor character.code.toLong()) * FNV_PRIME
    }

    hash = (hash xor (hash ushr 30)) * AVALANCHE_MULTIPLIER_1
    hash = (hash xor (hash ushr 27)) * AVALANCHE_MULTIPLIER_2
    return hash xor (hash ushr 31)
}
