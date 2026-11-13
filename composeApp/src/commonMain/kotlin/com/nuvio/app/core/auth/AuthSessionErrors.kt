package com.nuvio.app.core.auth

import io.github.jan.supabase.exceptions.RestException

private val invalidSessionMarkers = listOf(
    "session not found",
    "session_not_found",
    "invalid session",
    "invalid token",
    "invalid jwt",
    "user not found",
    "user does not exist",
)

private val invalidRefreshMarkers = listOf(
    "invalid refresh token",
    "refresh token is not valid",
    "refresh token not found",
    "refresh_token_not_found",
    "invalid_grant",
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
        errorCode = restError?.error,
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
    if (statusCode == 401) return true

    val messages = message.lowercase()
    val invalidJwt = "jwt" in messages &&
        ("invalid" in messages || "expired" in messages || "malformed" in messages || "bad_jwt" in messages)
    val invalidSession = invalidSessionMarkers.any(messages::contains)
    val deletedUser = "user" in messages &&
        ("does not exist" in messages || "not found" in messages || "deleted" in messages)
    val invalidForeignKey = "foreign key" in messages &&
        ("auth.users" in messages || "user_id" in messages)

    return invalidJwt || invalidSession || deletedUser || invalidForeignKey
}

internal fun Throwable.isInvalidRefreshError(): Boolean {
    val statusCode = findCause<RestException>()?.statusCode ?: return false
    if (statusCode !in setOf(400, 401, 403)) return false
    val messages = authMessages()
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

private fun Throwable.authMessages(): String = buildString {
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
