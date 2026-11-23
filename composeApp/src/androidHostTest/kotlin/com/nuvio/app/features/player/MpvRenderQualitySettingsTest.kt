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

    @Test
    fun debandDefaultsOffAndPersistsAcrossReload() {
        assertEquals(false, PlayerSettingsRepository.uiState.value.androidLibmpvDebandEnabled)

        PlayerSettingsRepository.setAndroidLibmpvDebandEnabled(true)
        assertEquals(true, PlayerSettingsStorage.loadAndroidLibmpvDebandEnabled())

        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(true, PlayerSettingsRepository.uiState.value.androidLibmpvDebandEnabled)
    }

    @Test
    fun debandIsIncludedInSettingsSyncPayload() {
        PlayerSettingsRepository.setAndroidLibmpvDebandEnabled(true)
        val payload = PlayerSettingsStorage.exportToSyncPayload()

        PlayerSettingsStorage.saveAndroidLibmpvDebandEnabled(false)
        PlayerSettingsStorage.replaceFromSyncPayload(payload)

        assertEquals(true, PlayerSettingsStorage.loadAndroidLibmpvDebandEnabled())
    }

    @Test
    fun scalingFiltersPersistAcrossReload() {
        PlayerSettingsRepository.setAndroidLibmpvUpscaler(AndroidLibmpvScalingFilter.EwaLanczos)
        PlayerSettingsRepository.setAndroidLibmpvDownscaler(AndroidLibmpvScalingFilter.Mitchell)
        PlayerSettingsRepository.setAndroidLibmpvChromaScaler(AndroidLibmpvScalingFilter.Spline36)

        assertEquals("EwaLanczos", PlayerSettingsStorage.loadAndroidLibmpvUpscaler())
        assertEquals("Mitchell", PlayerSettingsStorage.loadAndroidLibmpvDownscaler())
        assertEquals("Spline36", PlayerSettingsStorage.loadAndroidLibmpvChromaScaler())

        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()

        assertEquals(AndroidLibmpvScalingFilter.EwaLanczos, PlayerSettingsRepository.uiState.value.androidLibmpvUpscaler)
        assertEquals(AndroidLibmpvScalingFilter.Mitchell, PlayerSettingsRepository.uiState.value.androidLibmpvDownscaler)
        assertEquals(AndroidLibmpvScalingFilter.Spline36, PlayerSettingsRepository.uiState.value.androidLibmpvChromaScaler)
    }

    @Test
    fun scalingFiltersAreIncludedInSettingsSyncPayload() {
        PlayerSettingsRepository.setAndroidLibmpvUpscaler(AndroidLibmpvScalingFilter.Lanczos)
        PlayerSettingsRepository.setAndroidLibmpvDownscaler(AndroidLibmpvScalingFilter.CatmullRom)
        PlayerSettingsRepository.setAndroidLibmpvChromaScaler(AndroidLibmpvScalingFilter.Bilinear)
        val payload = PlayerSettingsStorage.exportToSyncPayload()

        PlayerSettingsStorage.saveAndroidLibmpvUpscaler(AndroidLibmpvScalingFilter.Default.name)
        PlayerSettingsStorage.saveAndroidLibmpvDownscaler(AndroidLibmpvScalingFilter.Default.name)
        PlayerSettingsStorage.saveAndroidLibmpvChromaScaler(AndroidLibmpvScalingFilter.Default.name)
        PlayerSettingsStorage.replaceFromSyncPayload(payload)

        assertEquals("Lanczos", PlayerSettingsStorage.loadAndroidLibmpvUpscaler())
        assertEquals("CatmullRom", PlayerSettingsStorage.loadAndroidLibmpvDownscaler())
        assertEquals("Bilinear", PlayerSettingsStorage.loadAndroidLibmpvChromaScaler())
    }

    @Test
    fun unknownStoredScalingFiltersFallBackToDefault() {
        PlayerSettingsStorage.saveAndroidLibmpvUpscaler("UnsupportedFilter")
        PlayerSettingsStorage.saveAndroidLibmpvDownscaler("UnsupportedFilter")
        PlayerSettingsStorage.saveAndroidLibmpvChromaScaler("UnsupportedFilter")

        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()

        assertEquals(AndroidLibmpvScalingFilter.Default, PlayerSettingsRepository.uiState.value.androidLibmpvUpscaler)
        assertEquals(AndroidLibmpvScalingFilter.Default, PlayerSettingsRepository.uiState.value.androidLibmpvDownscaler)
        assertEquals(AndroidLibmpvScalingFilter.Default, PlayerSettingsRepository.uiState.value.androidLibmpvChromaScaler)
    }

}
