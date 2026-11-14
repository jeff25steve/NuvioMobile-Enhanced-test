package com.nuvio.app.core.ui

import android.graphics.Bitmap
import coil3.Image
import coil3.toBitmap

internal actual fun sampleBackdropPixels(image: Image, targetWidth: Int): SampledPixels? {
    val source = image.toBitmap()
    if (source.width <= 0 || source.height <= 0) return null
    val software = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        source
    }
    val width = software.width
    val height = software.height
    val pixels = IntArray(width * height)
    software.getPixels(pixels, 0, width, 0, 0, width, height)
    if (software !== source) software.recycle()
    return downsampleArgb(pixels, width, height, targetWidth)
}

/** Nearest-neighbour ARGB reduction that preserves the input aspect ratio. */
internal fun downsampleArgb(
    pixels: IntArray,
    width: Int,
    height: Int,
    targetWidth: Int,
): SampledPixels? {
    if (width <= 0 || height <= 0 || targetWidth <= 0) return null
    if (pixels.size < width * height) return null

    val sampledWidth = minOf(width, targetWidth)
    val sampledHeight = ((height.toLong() * sampledWidth + width / 2L) / width)
        .coerceAtLeast(1L)
        .toInt()
    val sampled = IntArray(sampledWidth * sampledHeight)
    for (y in 0 until sampledHeight) {
        val sourceY = (((2L * y + 1L) * height) / (2L * sampledHeight))
            .toInt().coerceIn(0, height - 1)
        for (x in 0 until sampledWidth) {
            val sourceX = (((2L * x + 1L) * width) / (2L * sampledWidth))
                .toInt().coerceIn(0, width - 1)
            sampled[y * sampledWidth + x] = pixels[sourceY * width + sourceX]
        }
    }
    return SampledPixels(sampled, sampledWidth, sampledHeight)
}
}
