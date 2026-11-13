package com.nuvio.app.core.auth

import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException

private val invalidSessionMarkers = listOf(
    "session not found",
    "session_not_found",
    "invalid session",
    "invalid token",
    "invalid jwt",
    "user not found",
    "user does not exist",
)

private val invalidRefreshErrorCodes = setOf(
    "refresh_token_not_found",
    "refresh_token_already_used",
    "session_not_found",
    "session_expired",
    "user_banned",
)

private val invalidRefreshMarkers = listOf(
    "invalid refresh token",
    "refresh token is not valid",
    "refresh token not found",
    "refresh_token_not_found",
    "session not found",
    "session_not_found",
    "invalid session",
)

internal inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

internal fun Throwable.isJwtExpiredError(): Boolean = "jwt expired" in authMessages()

internal fun Throwable.isInvalidSessionError(): Boolean {
    val restError = findCause<RestException>()
    return isInvalidRemoteSessionResponse(
        statusCode = restError?.statusCode,
        errorCode = findCause<PostgrestRestException>()?.code ?: restError?.error,
        message = authMessages(),
    )
}

/**
 * Distinguishes an invalid remote session from a request that the current role is not
 * permitted to perform. PostgREST SQLSTATE 42501 can be surfaced as either 401 or 403;
 * neither status alone means the user's Supabase session is invalid.
 */
internal fun isInvalidRemoteSessionResponse(
    statusCode: Int?,
    message: String,
    errorCode: String? = null,
): Boolean {
    if (errorCode.equals("42501", ignoreCase = true)) return false

    val messages = message.lowercase()
    // A bad project key or missing API key is a backend configuration failure, not
    // evidence that the end user's persisted Supabase session should be discarded.
    if (
        errorCode.equals("invalid_api_key", ignoreCase = true) ||
        "invalid api key" in messages ||
        "api key is required" in messages
    ) return false

    val invalidJwt = "jwt" in messages &&
        ("invalid" in messages || "expired" in messages || "malformed" in messages || "bad_jwt" in messages)
    val invalidSession = invalidSessionMarkers.any(messages::contains)
    val deletedUser = "user" in messages &&
        ("does not exist" in messages || "not found" in messages || "deleted" in messages)
    val invalidForeignKey = "foreign key" in messages &&
        ("auth.users" in messages || "user_id" in messages)

    // HTTP 401 by itself is not enough: gateways and project-key failures also use it.
    // Only explicit session/JWT evidence should invalidate the locally stored identity.
    return statusCode in setOf(400, 401, 403, 404) &&
        (invalidJwt || invalidSession || deletedUser || invalidForeignKey)
}

internal fun Throwable.isInvalidRefreshError(): Boolean {
    val restError = findCause<RestException>() ?: return false
    val errorCode = restError.error
    return isInvalidRefreshResponse(
        statusCode = restError.statusCode,
        errorCode = errorCode,
        message = authMessages(),
    )
}

internal fun isInvalidRefreshResponse(
    statusCode: Int?,
    errorCode: String?,
    message: String,
): Boolean {
    if (statusCode !in setOf(400, 401, 403)) return false
    val normalizedCode = errorCode?.lowercase()
    if (normalizedCode in invalidRefreshErrorCodes) return true
    // A recognized Auth API code is authoritative: unrelated known failures must
    // not clear a valid session just because their message happens to mention a token.
    if (normalizedCode != null && AuthErrorCode.fromValue(normalizedCode) != null) return false
    val messages = message.lowercase()
    return invalidRefreshMarkers.any(messages::contains)
}

internal fun Throwable.isDeletedAccountError(): Boolean {
    val messages = authMessages()
    return (
        "user" in messages &&
            ("does not exist" in messages || "not found" in messages || "deleted" in messages)
        ) || (
        "foreign key" in messages &&
            ("auth.users" in messages || "user_id" in messages)
        )
}

internal fun Throwable.authMessages(): String = buildString {
    var current: Throwable? = this@authMessages
    while (current != null) {
        append(current.message.orEmpty())
        append(' ')
        if (current is RestException) {
            append(current.error)
            append(' ')
            append(current.description.orEmpty())
            append(' ')
        }
        current = current.cause
    }
}.lowercase()
