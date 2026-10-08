package com.nuvio.app.features.profiles

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
internal data class CachedProfilePinPayload(
    val userId: String = "",
    val salt: String,
    val digest: String,
    val profileUpdatedAt: String = "",
    val failedAttempts: Int = 0,
    val lockedUntilEpochSeconds: Long = 0,
)

internal fun generateProfilePinSalt(): String = Uuid.random().toString()

internal fun hashProfilePin(profileIndex: Int, salt: String, pin: String): String =
    ProfilePinCrypto.sha256Hex("profile:$profileIndex:$salt:$pin")