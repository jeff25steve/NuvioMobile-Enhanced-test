package com.nuvio.app.features.addons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddonPlatformAndroidTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun cancellingAddonManifestRequestCancelsUnderlyingOkHttpCall(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
        )

        val requestJob = launch(Dispatchers.IO) {
            httpGetTextWithHeaders(
                url = server.url("/manifest.json").toString(),
            )
        }

        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        requestJob.cancel()

        val completed = withTimeoutOrNull(2_000) {
            requestJob.join()
            true
        } ?: false

        assertTrue(
            completed,
            "Cancelling the addon request must cancel the underlying OkHttp Call instead of waiting for the 60s read timeout",
        )
    }
}
