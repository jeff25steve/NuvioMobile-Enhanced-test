package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MpvRenderQualitySettingsTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        PlayerSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
    }

    @AfterTest
    fun clearState() {
        PlayerSettingsRepository.clearLocalState()
    }

    @Test
    fun renderQualityDefaultsToBalancedAndPersistsAcrossReload() {
        assertEquals(
            AndroidLibmpvRenderQuality.Balanced,
            PlayerSettingsRepository.uiState.value.androidLibmpvRenderQuality,
        )

        PlayerSettingsRepository.setAndroidLibmpvRenderQuality(AndroidLibmpvRenderQuality.HighQuality)
        assertEquals("HighQuality", PlayerSettingsStorage.loadAndroidLibmpvRenderQuality())

        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(
            AndroidLibmpvRenderQuality.HighQuality,
            PlayerSettingsRepository.uiState.value.androidLibmpvRenderQuality,
        )
    }

    @Test
    fun renderQualityIsIncludedInSettingsSyncPayload() {
        PlayerSettingsRepository.setAndroidLibmpvRenderQuality(AndroidLibmpvRenderQuality.Performance)
        val payload = PlayerSettingsStorage.exportToSyncPayload()

        PlayerSettingsStorage.saveAndroidLibmpvRenderQuality(AndroidLibmpvRenderQuality.Balanced.name)
        PlayerSettingsStorage.replaceFromSyncPayload(payload)

        assertEquals(
            AndroidLibmpvRenderQuality.Performance.name,
            PlayerSettingsStorage.loadAndroidLibmpvRenderQuality(),
        )
    }
}
