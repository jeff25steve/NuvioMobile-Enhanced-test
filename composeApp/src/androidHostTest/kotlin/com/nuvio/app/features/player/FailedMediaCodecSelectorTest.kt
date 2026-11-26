@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoDecoderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FailedMediaCodecSelectorTest {
    @Test
    fun failedCodecIdentityIncludesMimeAndNameNotCapabilityFlags() {
        val secureCapableCodec = codec("codec.avc.hw", secure = true)
        val nonSecureVariant = codec("codec.avc.hw", secure = false)
        assertEquals(
            FailedMediaCodecIdentity(
                mimeType = MimeTypes.VIDEO_H264,
                name = "codec.avc.hw",
            ),
            secureCapableCodec.failedMediaCodecIdentity(),
        )
        assertEquals(
            secureCapableCodec.failedMediaCodecIdentity(),
            nonSecureVariant.failedMediaCodecIdentity(),
        )
    }

    @Test
    fun selectorExcludesOnlyTheExactFailedCodecIdentity() {
        val failed = codec("codec.avc.hw")
        val alternate = codec("codec.avc.alt")
        val sameNameOtherMime = codec("codec.avc.hw", mimeType = MimeTypes.VIDEO_H265)
        val secureVariant = codec("codec.avc.hw", secure = true)
        val delegate = FixedMediaCodecSelector(listOf(failed, alternate, sameNameOtherMime, secureVariant))
        val excluded = setOf(failed.failedMediaCodecIdentity())

        val result = FailedMediaCodecSelector(delegate, excluded)
            .getDecoderInfos(MimeTypes.VIDEO_H264, requiresSecureDecoder = false, requiresTunnelingDecoder = false)

        assertEquals(listOf(alternate), result)
        assertEquals(
            listOf(sameNameOtherMime),
            FailedMediaCodecSelector(delegate, excluded)
                .getDecoderInfos(MimeTypes.VIDEO_H265, requiresSecureDecoder = false, requiresTunnelingDecoder = false),
        )
    }

    @Test
    fun secureAndTunneledRequirementsAreAppliedByTheActiveDelegateQuery() {
        val failedSecure = codec("codec.avc.hw", secure = true)
        val alternateSecure = codec("codec.avc.alt", secure = true)
        val nonSecureVariant = codec("codec.avc.hw", secure = false)
        val alternateNonSecure = codec("codec.avc.alt", secure = false)
        val delegate = FixedMediaCodecSelector(listOf(failedSecure, alternateSecure, nonSecureVariant, alternateNonSecure))
        val selector = FailedMediaCodecSelector(delegate, setOf(failedSecure.failedMediaCodecIdentity()))

        assertEquals(
            listOf(alternateSecure),
            selector.getDecoderInfos(
                MimeTypes.VIDEO_H264,
                requiresSecureDecoder = true,
                requiresTunnelingDecoder = false,
            ),
        )
        assertEquals(
            listOf(alternateNonSecure),
            selector.getDecoderInfos(
                MimeTypes.VIDEO_H264,
                requiresSecureDecoder = false,
                requiresTunnelingDecoder = false,
            ),
        )
    }

    @Test
    fun selectorRetainsOriginalCandidatesIfFilteringWouldRemoveEveryDecoder() {
        val only = codec("codec.avc.hw")
        val delegate = FixedMediaCodecSelector(listOf(only))
        val selector = FailedMediaCodecSelector(delegate, setOf(only.failedMediaCodecIdentity()))

        assertEquals(
            listOf(only),
            selector.getDecoderInfos(
                MimeTypes.VIDEO_H264,
                requiresSecureDecoder = false,
                requiresTunnelingDecoder = false,
            ),
        )
    }

    @Test
    fun failedCodecExclusionSetIsDeduplicatedAndBoundedPerTrack() {
        val failed = codec("codec.avc.hw").failedMediaCodecIdentity()
        val alternate = codec("codec.avc.alt").failedMediaCodecIdentity()
        val alreadyExcluded = codec("codec.avc.alt2").failedMediaCodecIdentity()

        val planned = newFailedMediaCodecExclusions(
            failedMediaCodecs = listOf(failed, failed),
            previouslyExcluded = emptySet(),
            maxExcludedCount = 2,
        )
        assertEquals(listOf(failed), planned)

        val alreadyFailedAgain = newFailedMediaCodecExclusions(
            failedMediaCodecs = listOf(failed),
            previouslyExcluded = setOf(failed),
            maxExcludedCount = 2,
        )
        assertTrue(alreadyFailedAgain.isEmpty())

        val capped = newFailedMediaCodecExclusions(
            failedMediaCodecs = listOf(failed, alternate),
            previouslyExcluded = setOf(alreadyExcluded),
            maxExcludedCount = 2,
        )
        assertEquals(1, capped.size)
        assertFalse(capped.contains(alreadyExcluded))
    }

    @Test
    fun genericDecoderExceptionReportsTheExactCodecThatFailed() {
        val failed = codec("codec.aac.hw", mimeType = MimeTypes.AUDIO_AAC)
        val error = MediaCodecDecoderException(IllegalStateException("audio codec failed"), failed)

        assertEquals(listOf(failed.failedMediaCodecIdentity()), failedMediaCodecIdentities(error))
    }

    @Test
    fun videoDecoderExceptionSubclassIsRecognizedThroughItsCauseChain() {
        val failed = codec("codec.avc.hw")
        val decoderError = MediaCodecVideoDecoderException(
            IllegalStateException("video codec failed"),
            failed,
            null,
        )
        val wrappedError = IllegalStateException("renderer failure", decoderError)

        assertEquals(listOf(failed.failedMediaCodecIdentity()), failedMediaCodecIdentities(wrappedError))
    }

    @Test
    fun decoderInitializationExceptionReportsTheExactCodecThatFailed() {
        val failed = codec("codec.avc.hw")
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val error = androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException(
            format,
            IllegalStateException("codec initialization failed"),
            false,
            failed,
        )

        assertEquals(listOf(failed.failedMediaCodecIdentity()), failedMediaCodecIdentities(error))
    }

    private fun codec(
        name: String,
        mimeType: String = MimeTypes.VIDEO_H264,
        secure: Boolean = false,
    ): MediaCodecInfo = MediaCodecInfo.newInstance(
        name,
        mimeType,
        mimeType,
        null,
        true,
        false,
        true,
        true,
        secure,
    )

    private class FixedMediaCodecSelector(
        private val decoders: List<MediaCodecInfo>,
    ) : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): List<MediaCodecInfo> = decoders.filter {
            it.mimeType == mimeType &&
                it.secure == requiresSecureDecoder &&
                it.tunneling == requiresTunnelingDecoder
        }
    }
}
