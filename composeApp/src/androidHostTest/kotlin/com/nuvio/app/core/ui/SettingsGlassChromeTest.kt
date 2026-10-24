package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.glass.LiquidGlassMaterial
import com.nuvio.app.core.ui.glass.LiquidGlassSettings
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import com.nuvio.app.core.ui.glass.LiquidGlassSurfaceOverrides
import com.nuvio.app.core.ui.glass.LiquidGlassSurfacesSettings
import com.nuvio.app.core.ui.glass.LocalLiquidGlassBackdrop
import com.nuvio.app.core.ui.glass.nuvioBackdropSource
import com.nuvio.app.core.ui.glass.rememberNuvioBackdrop
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPixelCopy

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, shadows = [ShadowPixelCopy::class], minSdk = 26)
class SettingsGlassChromeTest {
    @get:Rule
    val compose = createComposeRule()

    private var originalMasterEnabled = true
    private var originalSurfaceOverrides = LiquidGlassSurfacesSettings()

    @BeforeTest
    fun preserveGlassSettings() {
        originalMasterEnabled = LiquidGlassSettings.enabled
        originalSurfaceOverrides = LiquidGlassSettings.surfaceOverrides
        LiquidGlassSettings.enabled = true
        LiquidGlassSettings.surfaceOverrides = LiquidGlassSurfacesSettings().withSurface(
            LiquidGlassSurface.SETTINGS,
            LiquidGlassSurfaceOverrides(
                enabled = true,
                cornerRadiusPercent = 50f,
                // This test checks backdrop isolation and rounded clipping, not the runtime-shader lens.
                material = LiquidGlassMaterial.FROSTED,
            ),
        )
    }

    @AfterTest
    fun restoreGlassSettings() {
        LiquidGlassSettings.enabled = originalMasterEnabled
        LiquidGlassSettings.surfaceOverrides = originalSurfaceOverrides
    }

    @Test
    fun roundedSettingsHeaderCornersShowOnlyTheStaticBackdropNotScrollingRows() {
        compose.setContent {
            NuvioTheme {
                val backdrop = rememberNuvioBackdrop()
                Box(Modifier.size(width = 320.dp, height = 180.dp).testTag("settings-glass-regression-root")) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .nuvioBackdropSource(backdrop),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Green),
                        )
                    }
                    // A red sibling simulates scrolling Settings content passing behind a sticky header.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .background(Color.Red),
                    )
                    CompositionLocalProvider(LocalLiquidGlassBackdrop provides backdrop) {
                        NuvioScreenHeader(
                            title = "Settings",
                            includeStatusBarPadding = false,
                            topPadding = 0.dp,
                            useSettingsGlass = true,
                        )
                    }
                }
            }
        }

        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("settings-glass-regression-root").captureToImage().toPixelMap()
        val topCorner = pixels[2, 2]

        assertTrue(
            topCorner.green > topCorner.red && topCorner.green > topCorner.blue,
            "The rounded header corner must reveal the static green backdrop, never the red scrolling content.",
        )
    }
}
