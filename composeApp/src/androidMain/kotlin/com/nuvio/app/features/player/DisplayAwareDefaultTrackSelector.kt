@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector

/**
 * Keeps ExoPlayer's automatic video selection aligned with the HDR formats the default display
 * currently reports it can present. Unsupported HDR is excluded only when a supported SDR
 * alternative exists, so HDR-only streams remain playable.
 */
internal class DisplayAwareDefaultTrackSelector(context: Context) : DefaultTrackSelector(context) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val displayListenerHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var supportedHdrTypes: Set<Int>? = null

    private var displayListenerRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refreshDisplayHdrTypes()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refreshDisplayHdrTypes()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refreshDisplayHdrTypes()
        }
    }

    init {
        if (displayManager != null) {
            displayListenerRegistered = runCatching {
                displayManager.registerDisplayListener(displayListener, displayListenerHandler)
            }.isSuccess
        }
        supportedHdrTypes = readDisplayHdrTypes()
    }

    override fun selectAllTracks(
        definitions: Array<ExoTrackSelection.Definition?>,
        mappedTrackInfo: MappingTrackSelector.MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: DefaultTrackSelector.Parameters,
    ) {
        val displayHdrTypes = supportedHdrTypes
        if (!shouldApplyDisplayHdrFiltering(displayHdrTypes, params.forceLowestBitrate)) {
            super.selectAllTracks(
                definitions,
                mappedTrackInfo,
                rendererFormatSupports,
                rendererMixedMimeTypeAdaptationSupports,
                params,
            )
            return
        }

        // Work on a copy: renderer capabilities are owned by Media3 and must not be mutated.
        val adjustedSupports = Array(rendererFormatSupports.size) { rendererIndex ->
            Array(rendererFormatSupports[rendererIndex].size) { groupIndex ->
                rendererFormatSupports[rendererIndex][groupIndex].clone()
            }
        }

        for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
            if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_VIDEO) continue

            val groups = mappedTrackInfo.getTrackGroups(rendererIndex)
            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                val groupSupports = rendererFormatSupports[rendererIndex][groupIndex]
                // TrackGroup contains alternative formats for the same content. An SDR track in
                // a different group may be a different camera angle/feed, not a valid fallback.
                val hasSdrFallback = hasSupportedSdrTrack(group, groupSupports)
                val hasSupportedHdrFallback = hasSupportedDisplayHdrTrack(
                    group,
                    groupSupports,
                    displayHdrTypes,
                )
                if (!hasSdrFallback && !hasSupportedHdrFallback) continue

                for (trackIndex in 0 until group.length) {
                    val supportFlags = groupSupports[trackIndex]
                    val format = group.getFormat(trackIndex)
                    val shouldMask = if (isSdrFallbackFormat(format)) {
                        shouldMaskSdrFormatForDisplay(
                            format = format,
                            rendererSupportFlags = supportFlags,
                            hasSupportedHdrFallback = hasSupportedHdrFallback,
                        )
                    } else {
                        shouldMaskHdrFormatForDisplay(
                            format = format,
                            rendererSupportFlags = supportFlags,
                            supportedHdrTypes = displayHdrTypes,
                            hasSdrFallback = hasSdrFallback,
                            hasSupportedHdrFallback = hasSupportedHdrFallback,
                        )
                    }
                    if (shouldMask) {
                        adjustedSupports[rendererIndex][groupIndex][trackIndex] = C.FORMAT_UNSUPPORTED_TYPE
                    }
                }
            }
        }

        super.selectAllTracks(
            definitions,
            mappedTrackInfo,
            adjustedSupports,
            rendererMixedMimeTypeAdaptationSupports,
            params,
        )
    }

    override fun release() {
        if (displayListenerRegistered) {
            displayManager?.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        super.release()
    }

    private fun refreshDisplayHdrTypes() {
        val updated = readDisplayHdrTypes()
        if (updated != supportedHdrTypes) {
            supportedHdrTypes = updated
            // TrackSelector.invalidate(null) is thread-safe and re-evaluates with current parameters.
            invalidate(null)
        }
    }

    private fun readDisplayHdrTypes(): Set<Int>? {
        return try {
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            displayHdrTypesFromCapabilities(display.hdrCapabilities?.supportedHdrTypes)
        } catch (_: RuntimeException) {
            // Unknown display state must not be treated as proof that HDR is unsupported.
            null
        }
    }
}

internal fun shouldMaskHdrFormatForDisplay(
    format: Format,
    rendererSupportFlags: Int,
    supportedHdrTypes: Set<Int>?,
    hasSdrFallback: Boolean,
    hasSupportedHdrFallback: Boolean = false,
): Boolean {
    if (
        (!hasSdrFallback && !hasSupportedHdrFallback) ||
        supportedHdrTypes == null ||
        !isRendererFormatSupported(rendererSupportFlags)
    ) return false

    val requiredHdrType = requiredDisplayHdrType(format, rendererSupportFlags) ?: return false
    return requiredHdrType !in supportedHdrTypes
}

internal fun hasSupportedDisplayHdrTrack(
    group: TrackGroup,
    groupSupports: IntArray,
    supportedHdrTypes: Set<Int>?,
): Boolean {
    if (supportedHdrTypes == null) return false
    for (trackIndex in 0 until minOf(group.length, groupSupports.size)) {
        val supportFlags = groupSupports[trackIndex]
        if (!isRendererFormatSupported(supportFlags)) continue
        val requiredHdrType = requiredDisplayHdrType(group.getFormat(trackIndex), supportFlags)
            ?: continue
        if (requiredHdrType in supportedHdrTypes) return true
    }
    return false
}

internal fun shouldMaskSdrFormatForDisplay(
    format: Format,
    rendererSupportFlags: Int,
    hasSupportedHdrFallback: Boolean,
): Boolean =
    hasSupportedHdrFallback &&
        isRendererFormatSupported(rendererSupportFlags) &&
        isSdrFallbackFormat(format)

private fun requiredDisplayHdrType(format: Format, rendererSupportFlags: Int): Int? {
    val transfer = format.colorInfo?.colorTransfer
    return when {
        isDolbyVisionFormat(format) &&
            RendererCapabilities.getDecoderSupport(rendererSupportFlags) !=
            RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE ->
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION

        transfer == C.COLOR_TRANSFER_ST2084 -> Display.HdrCapabilities.HDR_TYPE_HDR10
        transfer == C.COLOR_TRANSFER_HLG -> Display.HdrCapabilities.HDR_TYPE_HLG
        else -> null
    }
}

internal fun hasSupportedSdrTrack(
    group: TrackGroup,
    groupSupports: IntArray,
): Boolean {
    for (trackIndex in 0 until group.length) {
        if (isSupportedSdrFallback(group.getFormat(trackIndex), groupSupports[trackIndex])) {
            return true
        }
    }
    return false
}

internal fun isRendererFormatSupported(rendererSupportFlags: Int): Boolean =
    // FORMAT_EXCEEDS_CAPABILITIES is only a last-resort fallback, not a reliable SDR option.
    RendererCapabilities.isFormatSupported(rendererSupportFlags, false)

internal fun isSupportedSdrFallback(format: Format, rendererSupportFlags: Int): Boolean =
    isRendererFormatSupported(rendererSupportFlags) && isSdrFallbackFormat(format)

private fun isSdrFallbackFormat(format: Format): Boolean {
    if (isDolbyVisionFormat(format)) return false
    val colorInfo = format.colorInfo
    val transfer = colorInfo?.colorTransfer
    val explicitlySdrTransfer =
        colorInfo?.hdrStaticInfo == null &&
            (
                transfer == C.COLOR_TRANSFER_SDR ||
                    transfer == C.COLOR_TRANSFER_GAMMA_2_2 ||
                    transfer == C.COLOR_TRANSFER_SRGB
                )
    // isTransferHdr(false) is not proof of SDR: BT.2020/10-bit content with an unset
    // transfer characteristic is ambiguous. Use Media3's stricter assumed-SDR predicate.
    return ColorInfo.isEquivalentToAssumedSdrDefault(colorInfo) || explicitlySdrTransfer
}

private fun isDolbyVisionFormat(format: Format): Boolean {
    if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return true
    return format.codecs.orEmpty()
        .split(',')
        .asSequence()
        .map { it.trim() }
        .any { codec ->
            codec.startsWith("dvhe.") ||
                codec.startsWith("dvh1.") ||
                codec.startsWith("dvav.") ||
                codec.startsWith("dva1.") ||
                codec.startsWith("dav1.")
        }
}


internal fun shouldApplyDisplayHdrFiltering(
    supportedHdrTypes: Set<Int>?,
    forceLowestBitrate: Boolean,
): Boolean = supportedHdrTypes != null && !forceLowestBitrate


internal fun displayHdrTypesFromCapabilities(supportedHdrTypes: IntArray?): Set<Int>? =
    supportedHdrTypes?.toSet()
