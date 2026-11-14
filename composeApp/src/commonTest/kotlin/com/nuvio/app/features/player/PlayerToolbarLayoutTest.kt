package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlayerToolbarLayoutTest {
    @Test
    fun discTitlesIsAvailableInHiddenDefaultsWithoutChangingExistingVisibleControls() {
        val entry = PlayerToolbarLayout.Default.entries.single { it.button == PlayerToolbarButton.DISC_TITLES }

        assertFalse(entry.visible)
        assertEquals(
            listOf(
                PlayerToolbarButton.NEXT_EPISODE,
                PlayerToolbarButton.SPEED,
                PlayerToolbarButton.RESIZE,
                PlayerToolbarButton.SUBTITLES,
                PlayerToolbarButton.CHAPTERS,
                PlayerToolbarButton.NIGHT_MODE,
            ),
            PlayerToolbarLayout.Default.visibleButtons,
        )
    }

    @Test
    fun oldPersistedToolbarLayoutsGainDiscTitlesAsHiddenEntry() {
        val oldLayout = PlayerToolbarLayout.Default.entries
            .filterNot { it.button == PlayerToolbarButton.DISC_TITLES }
            .joinToString(",") { entry ->
                "${entry.button.storageId}:${if (entry.visible) "v" else "h"}"
            }

        val restored = PlayerToolbarLayout.decode(oldLayout)

        assertEquals(PlayerToolbarButton.DISC_TITLES, restored.entries.last().button)
        assertFalse(restored.entries.last().visible)
    }
}
