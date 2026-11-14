package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklAuthProfileScopeTest {
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
