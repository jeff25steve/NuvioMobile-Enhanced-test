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
            val hasSdrFallback = hasSupportedSdrTrack(
                groups = groups,
                groupSupports = rendererFormatSupports[rendererIndex],
            )
            if (!hasSdrFallback) continue

            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                for (trackIndex in 0 until group.length) {
                    val supportFlags = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                    if (
                        shouldMaskHdrFormatForDisplay(
                            format = group.getFormat(trackIndex),
                            rendererSupportFlags = supportFlags,
                            supportedHdrTypes = displayHdrTypes,
                            hasSdrFallback = true,
                        )
                    ) {
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

    private fun hasSupportedSdrTrack(
        groups: TrackGroupArray,
        groupSupports: Array<IntArray>,
    ): Boolean {
        for (groupIndex in 0 until groups.length) {
            val group: TrackGroup = groups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val supportFlags = groupSupports[groupIndex][trackIndex]
                if (
                    RendererCapabilities.isFormatSupported(supportFlags, true) &&
                    isSdrFallbackFormat(group.getFormat(trackIndex))
                ) {
                    return true
                }
            }
        }
        return false
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
): Boolean {
    if (
        !hasSdrFallback ||
        supportedHdrTypes == null ||
        !RendererCapabilities.isFormatSupported(rendererSupportFlags, true)
    ) return false

    val isDolbyVision = isDolbyVisionFormat(format)
    val transfer = format.colorInfo?.colorTransfer
    val requiredHdrType = when {
        isDolbyVision &&
            RendererCapabilities.getDecoderSupport(rendererSupportFlags) !=
            RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE ->
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION

        transfer == C.COLOR_TRANSFER_ST2084 -> Display.HdrCapabilities.HDR_TYPE_HDR10
        transfer == C.COLOR_TRANSFER_HLG -> Display.HdrCapabilities.HDR_TYPE_HLG
        else -> return false
    }

    return requiredHdrType !in supportedHdrTypes
}

private fun isSdrFallbackFormat(format: Format): Boolean =
    !ColorInfo.isTransferHdr(format.colorInfo) && !isDolbyVisionFormat(format)

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


internal fun displayHdrTypesFromCapabilities(supportedHdrTypes: IntArray?): Set<Int> =
    supportedHdrTypes?.toSet() ?: emptySet()
