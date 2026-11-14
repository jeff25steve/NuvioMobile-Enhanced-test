package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklAuthProfileScopeTest {
    @Test
    fun authInvalidationRequiresTheTokenToStillBelongToTheActiveProfile() {
        assertTrue(isSimklAuthProfileIdentityCurrent(loadedProfileId = 2, activeProfileId = 2))
        assertFalse(isSimklAuthProfileIdentityCurrent(loadedProfileId = 1, activeProfileId = 2))
        assertFalse(isSimklAuthProfileIdentityCurrent(loadedProfileId = null, activeProfileId = 2))
    }

    @Test
    fun authGenerationIsCurrentOnlyWhenItsLoadedProfileIsStillActive() {
        assertTrue(
            isSimklAuthProfileGenerationCurrent(
                expectedGeneration = 4L,
                currentGeneration = 4L,
                loadedProfileId = 2,
                activeProfileId = 2,
            ),
        )
        assertFalse(
            isSimklAuthProfileGenerationCurrent(
                expectedGeneration = 4L,
                currentGeneration = 4L,
                loadedProfileId = 1,
                activeProfileId = 2,
            ),
        )
        assertFalse(
            isSimklAuthProfileGenerationCurrent(
                expectedGeneration = 4L,
                currentGeneration = 5L,
                loadedProfileId = 2,
                activeProfileId = 2,
            ),
        )
    }
}
