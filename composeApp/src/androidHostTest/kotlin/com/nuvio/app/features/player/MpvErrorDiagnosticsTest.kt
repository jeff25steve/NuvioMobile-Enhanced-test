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
    fun cookieAssignmentsAreDroppedRatherThanPartiallyRedacted() {
        assertNull(sanitizeMpvLogMessage("http", "cookie=session-secret; clearance=private"))
        assertNull(sanitizeMpvLogMessage("http", "Set-Cookie=session-secret"))
    }

    @Test
    fun authorizationAssignmentsRedactBasicAndBearerValues() {
        val line = assertNotNull(
            sanitizeMpvLogMessage(
                "http",
                "Authorization=Basic basic-secret proxy-authorization=Bearer bearer-secret",
            ),
        )

        assertFalse(line.message.contains("basic-secret"))
        assertFalse(line.message.contains("bearer-secret"))
    }

    @Test
    fun cloudCredentialsAndClientSecretsAreRedacted() {
        val line = assertNotNull(
            sanitizeMpvLogMessage(
                "demux",
                "X-Amz-Credential=account%2Fdate client_secret=oauth-secret sessionid=session-secret",
            ),
        )

        assertFalse(line.message.contains("account%2Fdate"))
        assertFalse(line.message.contains("oauth-secret"))
        assertFalse(line.message.contains("session-secret"))
    }

    @Test
    fun endFileSummaryOnlyContainsAllowListedReasonAndSanitizedError() {
        val summary = sanitizeMpvEndFileDetails(
            reason = "error",
            fileError = "HTTP 403 opening https://user:password@cdn.example/stream.m3u8?token=private",
        )

        val formatted = summary.formatted()
        assertEquals("reason=error fileError=HTTP 403 opening [source URL]", formatted)
        assertFalse(formatted.contains("cdn.example"))
        assertFalse(formatted.contains("password"))
        assertFalse(formatted.contains("private"))
        assertEquals(
            "reason=unknown",
            sanitizeMpvEndFileDetails("error\\nraw-url=https://secret.invalid", null).formatted(),
        )
    }

    @Test
    fun awsAccessKeysAndCommonCredentialAssignmentsAreRedacted() {
        val line = assertNotNull(
            sanitizeMpvLogMessage(
                "demux",
                "keys AKIAIOSFODNN7EXAMPLE ASIAIOSFODNN7EXAMPLE " +
                    "aws_secret_access_key='secret value' session-id=private-session " +
                    "private_key=private-key-value x-amz-security-token=temporary-token",
            ),
        )

        assertFalse(line.message.contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(line.message.contains("ASIAIOSFODNN7EXAMPLE"))
        assertFalse(line.message.contains("secret value"))
        assertFalse(line.message.contains("private-session"))
        assertFalse(line.message.contains("private-key-value"))
        assertFalse(line.message.contains("temporary-token"))
    }

    @Test
    fun unknownAuthorizationSchemesAreRedactedThroughEndOfLine() {
        val line = assertNotNull(
            sanitizeMpvLogMessage(
                "http",
                "Authorization: Digest username=alice, response=private-proof request failed",
            ),
        )

        assertEquals("Authorization: [redacted]", line.message)
        assertFalse(line.message.contains("private-proof"))
    }

    @Test
    fun mpvPlaybackUrlLogsDropUserInfoQueryAndFragment() {
        val source = "https://user:password@cdn.example/stream.m3u8?token=private#session=fragment"

        assertEquals(
            "https://cdn.example/stream.m3u8",
            source.redactedPlaybackUrlForLogs(),
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
