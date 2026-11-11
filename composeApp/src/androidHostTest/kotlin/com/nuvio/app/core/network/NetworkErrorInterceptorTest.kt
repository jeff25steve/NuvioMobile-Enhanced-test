package com.nuvio.app.core.network

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import okhttp3.Interceptor
import okhttp3.Request

class NetworkErrorInterceptorTest {
    @Test
    fun unwrapsIoFailureWrappedByAndroidNetworkRuntimeException() {
        val ioFailure = IOException("TLS setup failed")
        val client = SharedOkHttpClient.base.newBuilder()
            .addInterceptor(Interceptor { throw RuntimeException("Android TLS setup", ioFailure) })
            .build()

        val actual = assertFailsWith<IOException> {
            client.newCall(Request.Builder().url("https://example.com/").build()).execute()
        }

        assertSame(ioFailure, actual)
    }

    @Test
    fun preservesRuntimeExceptionWithoutIoCause() {
        val failure = IllegalStateException("Invalid interceptor state")
        val client = SharedOkHttpClient.base.newBuilder()
            .addInterceptor(Interceptor { throw failure })
            .build()

        val actual = assertFailsWith<IllegalStateException> {
            client.newCall(Request.Builder().url("https://example.com/").build()).execute()
        }

        assertSame(failure, actual)
    }
}
