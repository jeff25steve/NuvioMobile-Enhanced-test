package com.nuvio.app.core.ui.glass

import android.app.Application
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiquidGlassTextContrastScopeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun contrastScopePreservesInheritedTextStyleAndAddsShadow() {
        val inheritedStyle = TextStyle(
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        var observedStyle: TextStyle? = null
        var observedTypography: Typography? = null

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTextStyle provides inheritedStyle) {
                    LiquidGlassTextContrastScope(enabled = true) {
                        observedStyle = LocalTextStyle.current
                        observedTypography = MaterialTheme.typography
                    }
                }
            }
        }

        compose.waitForIdle()

        assertEquals(inheritedStyle.fontSize, observedStyle?.fontSize)
        assertEquals(inheritedStyle.fontWeight, observedStyle?.fontWeight)
        assertEquals(inheritedStyle.letterSpacing, observedStyle?.letterSpacing)
        assertNotNull(observedStyle?.shadow)

        val typography = assertNotNull(observedTypography)
        val explicitStyles = listOf(
            typography.displayLarge,
            typography.displayMedium,
            typography.displaySmall,
            typography.headlineLarge,
            typography.headlineMedium,
            typography.headlineSmall,
            typography.titleLarge,
            typography.titleMedium,
            typography.titleSmall,
            typography.bodyLarge,
            typography.bodyMedium,
            typography.bodySmall,
            typography.labelLarge,
            typography.labelMedium,
            typography.labelSmall,
            typography.displayLargeEmphasized,
            typography.displayMediumEmphasized,
            typography.displaySmallEmphasized,
            typography.headlineLargeEmphasized,
            typography.headlineMediumEmphasized,
            typography.headlineSmallEmphasized,
            typography.titleLargeEmphasized,
            typography.titleMediumEmphasized,
            typography.titleSmallEmphasized,
            typography.bodyLargeEmphasized,
            typography.bodyMediumEmphasized,
            typography.bodySmallEmphasized,
            typography.labelLargeEmphasized,
            typography.labelMediumEmphasized,
            typography.labelSmallEmphasized,
        )
        assertTrue(explicitStyles.all { it.shadow != null })
    }
}
