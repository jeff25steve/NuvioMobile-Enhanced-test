package com.nuvio.app.features.profiles

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileAvatarBorderColorTest {
    @Test
    fun selectedProfileKeepsTheSelectedThemeBorderWhileInactiveProfileUsesOverride() {
        val override = Color(0xFFEC35F4)
        val avatar = Color(0xFF1688FF)
        val selected = Color(0xFF0055FF)

        assertEquals(
            selected,
            resolveProfileAvatarBorderColor(
                selected = true,
                avatarColor = avatar,
                selectedBorderColor = selected,
                defaultIconColorOverride = override,
            ),
        )
        assertEquals(
            override,
            resolveProfileAvatarBorderColor(
                selected = false,
                avatarColor = avatar,
                selectedBorderColor = selected,
                defaultIconColorOverride = override,
            ),
        )
    }

    @Test
    fun disabledOverridePreservesExistingAvatarBorderColors() {
        val avatar = Color(0xFF1688FF)
        val selected = Color(0xFF0055FF)

        assertEquals(
            selected,
            resolveProfileAvatarBorderColor(
                selected = true,
                avatarColor = avatar,
                selectedBorderColor = selected,
                defaultIconColorOverride = null,
            ),
        )
        assertEquals(
            avatar.copy(alpha = 0.5f),
            resolveProfileAvatarBorderColor(
                selected = false,
                avatarColor = avatar,
                selectedBorderColor = selected,
                defaultIconColorOverride = null,
            ),
        )
    }
}
