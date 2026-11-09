package com.nuvio.app.features.downloads

import kotlinx.coroutines.test.runTest
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_enqueue_missing_url
import nuvio.composeapp.generated.resources.downloads_enqueue_replaced
import nuvio.composeapp.generated.resources.downloads_enqueue_requires_wifi
import nuvio.composeapp.generated.resources.downloads_enqueue_started
import nuvio.composeapp.generated.resources.downloads_enqueue_unsupported_format
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadEnqueueResultMessageTest {
    @Test
    fun `formats each enqueue result with its localized message`() = runTest {
        assertEquals(getString(Res.string.downloads_enqueue_started), DownloadEnqueueResult.Started.toastMessage())
        assertEquals(getString(Res.string.downloads_enqueue_replaced), DownloadEnqueueResult.Replaced.toastMessage())
        assertEquals(getString(Res.string.downloads_enqueue_missing_url), DownloadEnqueueResult.MissingUrl.toastMessage())
        assertEquals(getString(Res.string.downloads_enqueue_unsupported_format), DownloadEnqueueResult.UnsupportedFormat.toastMessage())
        assertEquals(getString(Res.string.downloads_enqueue_requires_wifi), DownloadEnqueueResult.RequiresWifi.toastMessage())
    }
}
