package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppStartupMotionTest {
    @Test
    fun startupAnimationRemainsEnabledByDefault() {
        assertFalse(
            shouldSkipStartupLogoAnimation(
                skipStartupAnimation = false,
                platformPrefersReducedMotion = false,
            ),
        )
    }

    @Test
    fun explicitPreferenceSkipsStartupLogoAnimation() {
        assertTrue(
            shouldSkipStartupLogoAnimation(
                skipStartupAnimation = true,
                platformPrefersReducedMotion = false,
            ),
        )
    }

    @Test
    fun systemReducedMotionSkipsStartupLogoAnimationWithoutAppPreference() {
        assertTrue(
            shouldSkipStartupLogoAnimation(
                skipStartupAnimation = false,
                platformPrefersReducedMotion = true,
            ),
        )
    }

    @Test
    fun skippedIntroDoesNotHoldAuthenticationAndProfileGate() {
        assertFalse(
            shouldWaitForStartupLogoAnimation(
                skipStartupAnimation = true,
                platformPrefersReducedMotion = false,
                animationFinished = false,
            ),
        )
        assertFalse(
            shouldWaitForStartupLogoAnimation(
                skipStartupAnimation = false,
                platformPrefersReducedMotion = true,
                animationFinished = false,
            ),
        )
    }

    @Test
    fun enabledIntroHoldsGateOnlyUntilItsAnimationFinishes() {
        assertTrue(
            shouldWaitForStartupLogoAnimation(
                skipStartupAnimation = false,
                platformPrefersReducedMotion = false,
                animationFinished = false,
            ),
        )
        assertFalse(
            shouldWaitForStartupLogoAnimation(
                skipStartupAnimation = false,
                platformPrefersReducedMotion = false,
                animationFinished = true,
            ),
        )
    }
}
