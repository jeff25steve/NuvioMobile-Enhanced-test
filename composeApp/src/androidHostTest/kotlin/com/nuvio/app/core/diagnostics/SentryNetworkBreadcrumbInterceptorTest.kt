package com.nuvio.app.core.diagnostics

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals

class SentryNetworkBreadcrumbInterceptorTest {
    private val interceptor = SentryNetworkBreadcrumbInterceptor()

    @Test
    fun redactionRemovesCredentialsPathQueryAndFragment() {
        val url = "https://alice:secret@media.example:8443/private/library/manifest?token=hidden#section"
            .toHttpUrl()

        assertEquals(
            "https://media.example:8443/...",
            interceptor.scrubbedUrl(url),
        )
    }

    @Test
    fun redactionPreservesSchemeAndHostWithoutLeakingPathOrQuery() {
        val url = "http://media.example/private/item?session=hidden".toHttpUrl()

        assertEquals(
            "http://media.example/...",
            interceptor.scrubbedUrl(url),
        )
    }
}
