package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerOutputAudioProcessorTest {
    @Test
    fun keepsExplicitFiveOneAndSevenOneDownmixLayouts() {
        assertFalse(
            shouldStereoDownmixPcm(
                audioPassthroughEnabled = false,
                appDownmixEnabled = true,
                outputChannels = AudioOutputChannels.CHANNELS_5_1,
            ),
        )
        assertFalse(
            shouldStereoDownmixPcm(
                audioPassthroughEnabled = false,
                appDownmixEnabled = true,
                outputChannels = AudioOutputChannels.CHANNELS_7_1,
            ),
        )
    }

    @Test
    fun retainsDefaultStereoDownmixWhenAppDownmixIsDisabled() {
        assertTrue(
            shouldStereoDownmixPcm(
                audioPassthroughEnabled = false,
                appDownmixEnabled = false,
                outputChannels = AudioOutputChannels.CHANNELS_5_1,
            ),
        )
    }

    @Test
    fun doesNotApplyPcmDownmixWhenPassthroughIsEnabled() {
        assertFalse(
            shouldStereoDownmixPcm(
                audioPassthroughEnabled = true,
                appDownmixEnabled = true,
                outputChannels = AudioOutputChannels.CHANNELS_2_0,
            ),
        )
    }
}
