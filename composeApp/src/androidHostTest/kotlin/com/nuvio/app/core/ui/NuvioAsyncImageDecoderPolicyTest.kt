package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NuvioAsyncImageDecoderPolicyTest {
    @Test
    fun staticGifAndWebpRequestsUseTheFirstFrameDecoder() {
        assertTrue(shouldUseStaticImageDecoder("https://images.example/poster.gif", animateIfPossible = false))
        assertTrue(shouldUseStaticImageDecoder("https://images.example/poster.webp?token=abc", animateIfPossible = false))
        assertTrue(shouldUseStaticImageDecoder("https://images.example/poster.gif#frame", animateIfPossible = false))
    }

    @Test
    fun explicitAnimationOptInKeepsTheAnimatedDecoderAvailable() {
        assertFalse(shouldUseStaticImageDecoder("https://images.example/poster.gif", animateIfPossible = true))
        assertFalse(shouldUseStaticImageDecoder("https://images.example/poster.webp", animateIfPossible = true))
    }

    @Test
    fun staticAndAnimatedRequestsDoNotShareTheSameMemoryCacheEntry() {
        val staticKey = imageMemoryCacheKey(
            "https://images.example/poster.gif",
            animateIfPossible = false,
        )
        val animatedKey = imageMemoryCacheKey(
            "https://images.example/poster.gif",
            animateIfPossible = true,
        )

        assertFalse(staticKey == animatedKey)
    }

    @Test
    fun staticFormatsKeepTheDefaultDecoderPipeline() {
        assertFalse(shouldUseStaticImageDecoder("https://images.example/poster.jpg", animateIfPossible = false))
        assertFalse(shouldUseStaticImageDecoder("https://images.example/poster.svg", animateIfPossible = false))
    }
}
