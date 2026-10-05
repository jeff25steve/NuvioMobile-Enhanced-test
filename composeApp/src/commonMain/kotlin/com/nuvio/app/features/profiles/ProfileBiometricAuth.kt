package com.nuvio.app.features.profiles

enum class ProfileBiometricResult {
    Success,
    Cancelled,
    Unavailable,
    NotConfigured,
    Invalidated,
    Failed,
}

expect object ProfileBiometricAuth {
    fun initialize(host: Any)
    fun isAvailable(): Boolean
    fun isConfigured(profileIndex: Int): Boolean
    suspend fun enable(profileIndex: Int): ProfileBiometricResult
    suspend fun authenticate(profileIndex: Int): ProfileBiometricResult
    fun disable(profileIndex: Int)
}
