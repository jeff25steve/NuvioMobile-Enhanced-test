@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

internal const val MAX_FAILED_MEDIA_CODEC_EXCLUSIONS_PER_TRACK = 2

/**
 * Identifies the codec implementation, not its capabilities. Secure/tunnelling requirements are
 * query properties; MediaCodecInfo.secure/tunneling only say what a codec supports.
 */
internal data class FailedMediaCodecIdentity(
    val mimeType: String,
    val name: String,
)

internal fun MediaCodecInfo.failedMediaCodecIdentity(): FailedMediaCodecIdentity =
    FailedMediaCodecIdentity(mimeType = mimeType, name = name)

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

/** Bounds repeated codec-specific rebuilds to a small number per track and playback source. */
internal fun newFailedMediaCodecExclusions(
    failedMediaCodecs: List<FailedMediaCodecIdentity>,
    previouslyExcluded: Set<FailedMediaCodecIdentity>,
    maxExcludedCount: Int = MAX_FAILED_MEDIA_CODEC_EXCLUSIONS_PER_TRACK,
): List<FailedMediaCodecIdentity> {
    if (maxExcludedCount <= previouslyExcluded.size) return emptyList()

    val additions = ArrayList<FailedMediaCodecIdentity>()
    val considered = previouslyExcluded.toMutableSet()
    for (failed in failedMediaCodecs) {
        if (failed in considered) continue
        considered += failed
        additions += failed
        if (considered.size >= maxExcludedCount) break
    }
    return additions
}

/**
 * Filters only the decoder candidates returned for the current MIME/security/tunnelling query.
 * If the query has no alternative, it returns the original candidates so the existing renderer
 * priority/software fallback remains available.
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
        if (excludedCodecs.isEmpty()) return decoders

        val filtered = decoders.filterNot { decoder ->
            decoder.failedMediaCodecIdentity() in excludedCodecs
        }
        return filtered.ifEmpty { decoders }
    }
}
