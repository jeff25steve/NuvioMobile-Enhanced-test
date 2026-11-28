package com.nuvio.app.features.trakt

import com.nuvio.app.features.simkl.SimklPkceCrypto
import com.nuvio.app.features.simkl.base64UrlWithoutPadding
import com.nuvio.app.features.simkl.constantTimeEquals
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

internal const val TRAKT_AUTHORIZATION_TIMEOUT_MS = 5L * 60L * 1_000L

internal data class TraktPkceMaterial(
    val verifier: String,
    val challenge: String,
    val state: String,
)

internal fun generateTraktPkceMaterial(): TraktPkceMaterial =
    createTraktPkceMaterial(
        verifierEntropy = SimklPkceCrypto.secureRandomBytes(32),
        stateEntropy = SimklPkceCrypto.secureRandomBytes(32),
    )

internal fun createTraktPkceMaterial(
    verifierEntropy: ByteArray,
    stateEntropy: ByteArray,
): TraktPkceMaterial {
    require(verifierEntropy.size >= 32) { "PKCE verifier entropy must be at least 32 bytes" }
    require(stateEntropy.size >= 16) { "OAuth state entropy must be at least 16 bytes" }
    val verifier = verifierEntropy.base64UrlWithoutPadding()
    require(verifier.length in 43..128) { "PKCE verifier must be 43-128 characters" }
    return TraktPkceMaterial(
        verifier = verifier,
        challenge = SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding(),
        state = stateEntropy.base64UrlWithoutPadding(),
    )
}

internal fun buildTraktAuthorizationUrl(
    clientId: String,
    redirectUri: String,
    material: TraktPkceMaterial,
): String = buildString {
    append("https://auth.trakt.tv/oauth/authorize")
    append("?response_type=code")
    append("&client_id=")
    append(clientId.encodeURLParameter())
    append("&redirect_uri=")
    append(redirectUri.encodeURLParameter())
    append("&state=")
    append(material.state.encodeURLParameter())
    append("&code_challenge=")
    append(material.challenge.encodeURLParameter())
    append("&code_challenge_method=S256")
}

internal fun isTraktAuthorizationExpired(
    startedAtMillis: Long?,
    nowMillis: Long,
): Boolean = startedAtMillis == null ||
    nowMillis < startedAtMillis ||
    nowMillis - startedAtMillis > TRAKT_AUTHORIZATION_TIMEOUT_MS

internal fun traktAuthorizationStateMatches(
    expectedState: String?,
    callbackState: String,
): Boolean = !expectedState.isNullOrBlank() &&
    callbackState.isNotBlank() &&
    constantTimeEquals(callbackState, expectedState)

internal sealed interface TraktAuthCallback {
    data class AuthorizationCode(
        val code: String,
        val state: String,
    ) : TraktAuthCallback

    data class AuthorizationDenied(
        val description: String?,
        val state: String,
    ) : TraktAuthCallback

    data object Invalid : TraktAuthCallback
    data object NotTrakt : TraktAuthCallback
}

internal fun parseTraktAuthCallback(
    callbackUrl: String,
    redirectUri: String,
): TraktAuthCallback {
    if (callbackUrl != redirectUri && !callbackUrl.startsWith("$redirectUri?")) {
        return TraktAuthCallback.NotTrakt
    }
    val parsed = runCatching { Url(callbackUrl) }.getOrNull() ?: return TraktAuthCallback.Invalid
    val codes = parsed.parameters.getAll("code").orEmpty()
    val states = parsed.parameters.getAll("state").orEmpty()
    val errors = parsed.parameters.getAll("error").orEmpty()
    val descriptions = parsed.parameters.getAll("error_description").orEmpty()

    if (states.size != 1 || states.single().isBlank() || errors.size > 1 || descriptions.size > 1) {
        return TraktAuthCallback.Invalid
    }
    val error = errors.singleOrNull()?.trim()
    if (!error.isNullOrBlank()) {
        if (codes.isNotEmpty()) return TraktAuthCallback.Invalid
        return TraktAuthCallback.AuthorizationDenied(
            description = descriptions.singleOrNull()?.takeIf { it.isNotBlank() },
            state = states.single(),
        )
    }
    if (codes.size != 1 || codes.single().isBlank() || descriptions.isNotEmpty()) {
        return TraktAuthCallback.Invalid
    }
    return TraktAuthCallback.AuthorizationCode(
        code = codes.single().trim(),
        state = states.single().trim(),
    )
}
