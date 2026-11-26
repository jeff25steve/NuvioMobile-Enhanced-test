@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

internal const val MAX_FAILED_MEDIA_CODEC_EXCLUSIONS_PER_TRACK = 2

internal data class FailedMediaCodecIdentity(
    val mimeType: String,
    val name: String,
    val requiresSecureDecoder: Boolean,
    val requiresTunnelingDecoder: Boolean,
)

internal fun MediaCodecInfo.failedMediaCodecIdentity(): FailedMediaCodecIdentity =
    FailedMediaCodecIdentity(
        mimeType = mimeType,
        name = name,
        requiresSecureDecoder = secure,
        requiresTunnelingDecoder = tunneling,
    )

internal fun failedMediaCodecIdentities(error: Throwable): List<FailedMediaCodecIdentity> {
    val identities = LinkedHashSet<FailedMediaCodecIdentity>()
    val visited = HashSet<Throwable>()
    var current: Throwable? = error

    while (current != null && visited.add(current)) {
        when (val failure = current) {
            is MediaCodecDecoderException -> {
                failure.codecInfo?.failedMediaCodecIdentity()?.let(identities::add)
            }
            is MediaCodecRenderer.DecoderInitializationException -> {
                var initializationFailure: MediaCodecRenderer.DecoderInitializationException? = failure
                while (initializationFailure != null) {
                    initializationFailure.codecInfo
                        ?.failedMediaCodecIdentity()
                        ?.let(identities::add)
                    initializationFailure = initializationFailure.fallbackDecoderInitializationException
                }
            }
        }
        current = current.cause
    }

    return identities.toList()
}

/**
 * Returns only failed codecs that can be replaced by another decoder satisfying the same
 * MIME/security/tunnelling query. The cap bounds repeated rebuilds for one playback track.
 */
internal fun newFailedMediaCodecExclusions(
    failedMediaCodecs: List<FailedMediaCodecIdentity>,
    previouslyExcluded: Set<FailedMediaCodecIdentity>,
    maxExcludedCount: Int = MAX_FAILED_MEDIA_CODEC_EXCLUSIONS_PER_TRACK,
    availableDecoders: (FailedMediaCodecIdentity) -> List<FailedMediaCodecIdentity>,
): List<FailedMediaCodecIdentity> {
    if (maxExcludedCount <= previouslyExcluded.size) return emptyList()

    val considered = previouslyExcluded.toMutableSet()
    val additions = ArrayList<FailedMediaCodecIdentity>()
    for (failed in failedMediaCodecs) {
        if (failed in considered) continue

        val hasAlternative = availableDecoders(failed).any { candidate ->
            candidate != failed && candidate !in considered
        }
        if (!hasAlternative) continue

        considered += failed
        additions += failed
        if (considered.size >= maxExcludedCount) break
    }
    return additions
}

/**
 * Applies a playback-session-local codec exclusion list. If filtering would leave no candidates
 * for a query, retain the original list so existing software/renderer fallback remains possible.
 */
internal class FailedMediaCodecSelector(
    private val delegate: MediaCodecSelector,
    private val excludedCodecs: Set<FailedMediaCodecIdentity>,
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val decoders = delegate.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
        )
        val filtered = decoders.filterNot { decoder ->
            decoder.failedMediaCodecIdentity() in excludedCodecs
        }
        return filtered.ifEmpty { decoders }
    }
}
