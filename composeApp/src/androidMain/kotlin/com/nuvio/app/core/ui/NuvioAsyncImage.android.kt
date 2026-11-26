package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest

@Composable
internal actual fun NuvioAsyncImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
    fallbackImageUrl: String?,
) {
    val context = LocalContext.current
    val fallback = fallbackImageUrl?.takeIf { it.isNotBlank() && it != imageUrl }
    var useFallback by remember(imageUrl, fallback) { mutableStateOf(false) }
    val activeUrl = if (useFallback && fallback != null) fallback else imageUrl
    val request: ImageRequest = remember(context, activeUrl) {
        ImageRequest.Builder(context)
            .data(activeUrl)
            .memoryCacheKey(imageMemoryCacheKey(activeUrl, animateIfPossible))
            .diskCacheKey(activeUrl)
            .apply {
                if (shouldUseStaticImageDecoder(activeUrl, animateIfPossible)) {
                    // Coil's default animated decoders animate GIF/WebP even when the caller
                    // requests a static image. Force BitmapFactory to decode only the first frame.
                    decoderFactory(BitmapFactoryDecoder.Factory())
                }
            }
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onError = { if (fallback != null) useFallback = true },
    )
}

internal fun shouldUseStaticImageDecoder(
    imageUrl: String,
    animateIfPossible: Boolean,
): Boolean = !animateIfPossible && imageUrl.looksAnimatedImage()

internal fun imageMemoryCacheKey(
    imageUrl: String,
    animateIfPossible: Boolean,
): String {
    val decoderMode = if (shouldUseStaticImageDecoder(imageUrl, animateIfPossible)) "static" else "default"
    return "home-collection:$imageUrl:$decoderMode"
}

private fun String.looksAnimatedImage(): Boolean {
    val cleanUrl = substringBefore('?').substringBefore('#')
    return cleanUrl.endsWith(".gif", ignoreCase = true) ||
        cleanUrl.endsWith(".webp", ignoreCase = true)
}
