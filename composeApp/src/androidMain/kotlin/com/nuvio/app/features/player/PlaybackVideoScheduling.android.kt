package com.nuvio.app.features.player

import android.os.Build

/**
 * Media3's asynchronous MediaCodec queueing is enabled by default on Android 12+.
 * The renderer's duration-to-progress scheduling only takes effect with an asynchronous codec.
 */
internal fun shouldEnableMediaCodecVideoRendererDurationToProgressUs(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.S

internal fun averageVideoFrameProcessingOffsetUs(totalProcessingOffsetUs: Long, frameCount: Int): Long? =
    frameCount.takeIf { it > 0 }?.let { totalProcessingOffsetUs / it }
