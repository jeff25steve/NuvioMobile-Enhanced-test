package com.nuvio.app.features.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class AutoSyncPlaybackSettingsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun autoSyncCanBeEnabledWithoutPreferredSubtitleLanguage() {
        compose.setContent {
            NuvioTheme {
                Column {
                    AutoSyncPlaybackSettingsRows(
                        isTablet = false,
                        enabled = true,
                    )
                }
            }
        }

        compose.onNodeWithText("Auto Sync Subtitle").assertIsEnabled()
    }

    @Test
    fun externalPlayerStillDisablesAutoSync() {
        compose.setContent {
            NuvioTheme {
                Column {
                    AutoSyncPlaybackSettingsRows(
                        isTablet = false,
                        enabled = false,
                    )
                }
            }
        }

        compose.onNodeWithText("Auto Sync Subtitle").assertIsNotEnabled()
    }
}
