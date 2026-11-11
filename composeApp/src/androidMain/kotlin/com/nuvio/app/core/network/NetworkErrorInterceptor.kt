package com.nuvio.app.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Android TLS setup can surface an IOException wrapped in a RuntimeException.
 * OkHttp requires I/O failures to be IOException so async requests reach onFailure
 * instead of the uncaught-exception handler.
 */
internal class NetworkErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        try {
            chain.proceed(chain.request())
        } catch (error: RuntimeException) {
            throw error.cause as? IOException ?: error
        }
}
