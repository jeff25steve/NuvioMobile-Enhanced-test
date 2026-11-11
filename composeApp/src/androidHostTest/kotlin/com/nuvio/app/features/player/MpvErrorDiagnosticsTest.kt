package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvErrorDiagnosticsTest {
    @Test
    fun sourceUrlsAndSignedQueryValuesNeverReachDiagnosticText() {
        val line = assertNotNull(
            sanitizeMpvLogMessage(
                "ffmpeg",
                "HTTP error 403 while opening https://user:password@cdn.example/stream.m3u8?token=private&X-Amz-Signature=signed",
            ),
        )

        assertEquals("[ffmpeg] HTTP error 403 while opening [source URL]", line.formatted())
        assertFalse(line.formatted().contains("cdn.example"))
        assertFalse(line.formatted().contains("password"))
        assertFalse(line.formatted().contains("private"))
        assertFalse(line.formatted().contains("signed"))
    }

    @Test
    fun authorizationAndSecretAssignmentsAreRedacted() {
        val line = assertNotNull(
            sanitizeMpvLogMessage(
                "https",
                "Authorization: Bearer bearer-secret x-api-key=api-secret jwt=jwt-secret",
            ),
        )

        assertTrue("Authorization: [redacted]" in line.message)
        assertFalse(line.message.contains("bearer-secret"))
        assertFalse(line.message.contains("api-secret"))
        assertFalse(line.message.contains("jwt-secret"))
    }

    @Test
    fun cookieHeadersAreDroppedRatherThanPartiallyRedacted() {
        assertNull(
            sanitizeMpvLogMessage(
                "http",
                "Cookie: session=private; clearance=also-private",
            ),
        )
    }

    @Test
    fun queryStringsWithoutAnExplicitSchemeAreRedacted() {
        val line = assertNotNull(
            sanitizeMpvLogMessage(
                "demux",
                "Failed opening cdn.example/playlist.m3u8?sig=private&expires=10",
            ),
        )

        assertTrue("?[redacted]" in line.message)
        assertFalse(line.message.contains("private"))
        assertFalse(line.message.contains("expires=10"))
    }

    @Test
    fun diagnosticLinesAreFlattenedAndBounded() {
        val line = assertNotNull(sanitizeMpvLogMessage("cplayer", "TLS\nhandshake\nfailed " + "x".repeat(300)))

        assertFalse(line.message.contains('\n'))
        assertFalse(line.message.contains('\r'))
        assertTrue(line.message.length <= 160)
        assertNull(sanitizeMpvLogMessage("  ", " \n "))
    }

    @Test
    fun invalidModulePrefixesCannotInjectDiagnosticFormatting() {
        val line = assertNotNull(sanitizeMpvLogMessage("ffmpeg\n[forged]", "I/O error"))

        assertEquals("ffmpegforged", line.prefix)
    }
}
