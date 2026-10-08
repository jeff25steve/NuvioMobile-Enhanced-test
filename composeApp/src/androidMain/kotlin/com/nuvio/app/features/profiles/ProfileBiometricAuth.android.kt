package com.nuvio.app.features.profiles

import android.os.Build
import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import java.security.KeyStore
import java.security.KeyStoreException
import android.security.keystore.KeyPermanentlyInvalidatedException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

actual object ProfileBiometricAuth {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_PREFIX = "nuvio_primary_profile_biometric_"
    private const val LEGACY_ALIAS = "nuvio_primary_profile_biometric_1"
    private val sentinel = "nuvio-profile-biometric".encodeToByteArray()

    private var activityReference: WeakReference<FragmentActivity>? = null

    actual fun initialize(host: Any) {
        activityReference = (host as? FragmentActivity)?.let(::WeakReference)
        // Remove the pre-account-bound credential so a rollback cannot reuse it across accounts.
        deleteLegacyKey()
    }

    private fun activity(): FragmentActivity? = activityReference?.get()

    actual fun isAvailable(): Boolean {
        val host = activity() ?: return false
        val keyguard = host.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard != null && !keyguard.isDeviceSecure) return false
        return BiometricManager.from(host).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    actual suspend fun isConfigured(profileIndex: Int, userId: String): Boolean {
        if (profileIndex != 1 || userId.isBlank()) return false
        return runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            keyStore.containsAlias(alias(profileIndex, userId))
        }.getOrDefault(false)
    }

    actual suspend fun enable(profileIndex: Int, userId: String): ProfileBiometricResult {
        if (profileIndex != 1 || userId.isBlank() || !isAvailable()) {
            return ProfileBiometricResult.Unavailable
        }

        deleteKey(profileIndex, userId)
        deleteLegacyKey()
        return runCatching {
            generateKey(profileIndex, userId)
            when (val result = authenticateInternal(profileIndex, userId, setup = true)) {
                ProfileBiometricResult.Success -> result
                else -> {
                    deleteKey(profileIndex, userId)
                    result
                }
            }
        }.getOrElse {
            deleteKey(profileIndex, userId)
            ProfileBiometricResult.Failed
        }
    }

    actual suspend fun authenticate(profileIndex: Int, userId: String): ProfileBiometricResult {
        if (profileIndex != 1 || userId.isBlank()) return ProfileBiometricResult.Unavailable
        if (!isConfigured(profileIndex, userId)) return ProfileBiometricResult.NotConfigured

        return runCatching {
            authenticateInternal(profileIndex, userId, setup = false)
        }.getOrElse { error ->
            if (error is KeyPermanentlyInvalidatedException) {
                deleteKey(profileIndex, userId)
                ProfileBiometricResult.Invalidated
            } else {
                ProfileBiometricResult.Failed
            }
        }
    }

    actual fun disable(profileIndex: Int, userId: String) {
        if (profileIndex != 1) return
        if (userId.isNotBlank()) deleteKey(profileIndex, userId)
        deleteLegacyKey()
    }

    private suspend fun authenticateInternal(
        profileIndex: Int,
        userId: String,
        setup: Boolean,
    ): ProfileBiometricResult {
        val host = activity() ?: return ProfileBiometricResult.Unavailable
        val biometricManager = BiometricManager.from(host)
        if (
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
                BiometricManager.BIOMETRIC_SUCCESS
        ) {
            return ProfileBiometricResult.Unavailable
        }

        val cipher = createCipher(profileIndex, userId)
        val cryptoObject = BiometricPrompt.CryptoObject(cipher)
        val promptTitle = getString(
            if (setup) {
                Res.string.profile_biometric_prompt_setup_title
            } else {
                Res.string.profile_biometric_prompt_unlock_title
            },
        )
        val promptSubtitle = getString(
            if (setup) {
                Res.string.profile_biometric_prompt_setup_subtitle
            } else {
                Res.string.profile_biometric_prompt_unlock_subtitle
            },
        )
        val negativeButtonText = getString(
            if (setup) {
                Res.string.action_cancel
            } else {
                Res.string.profile_biometric_prompt_use_pin
            },
        )

        return suspendCancellableCoroutine { continuation ->
            val executor = host.mainExecutor
            val prompt = BiometricPrompt(
                host,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        val authenticatedCipher = result.cryptoObject?.cipher
                        if (authenticatedCipher == null || authenticatedCipher !== cipher) {
                            if (continuation.isActive) continuation.resume(ProfileBiometricResult.Failed)
                            return
                        }

                        val outcome = runCatching {
                            authenticatedCipher.doFinal(sentinel)
                            ProfileBiometricResult.Success
                        }.getOrElse { error ->
                            if (error is KeyPermanentlyInvalidatedException) {
                                deleteKey(profileIndex, userId)
                                ProfileBiometricResult.Invalidated
                            } else {
                                ProfileBiometricResult.Failed
                            }
                        }
                        if (continuation.isActive) continuation.resume(outcome)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        continuation.resume(
                            when (errorCode) {
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
                                    if (setup) {
                                        ProfileBiometricResult.Cancelled
                                    } else {
                                        ProfileBiometricResult.FallbackRequested
                                    }
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_CANCELED -> ProfileBiometricResult.Cancelled
                                BiometricPrompt.ERROR_NO_BIOMETRICS,
                                BiometricPrompt.ERROR_HW_NOT_PRESENT,
                                BiometricPrompt.ERROR_HW_UNAVAILABLE,
                                BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                                BiometricPrompt.ERROR_LOCKOUT,
                                BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                                BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED ->
                                    ProfileBiometricResult.Unavailable
                                else -> ProfileBiometricResult.Failed
                            },
                        )
                    }

                    override fun onAuthenticationFailed() {
                        // Keep the system prompt open for another biometric attempt.
                    }
                },
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(promptTitle)
                .setSubtitle(promptSubtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(negativeButtonText)
                .setConfirmationRequired(false)
                .build()

            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(promptInfo, cryptoObject)
        }
    }

    private fun generateKey(profileIndex: Int, userId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generateKey(profileIndex, userId, strongBox = true)
                return
            } catch (_: Exception) {
                deleteKey(profileIndex, userId)
            }
        }
        generateKey(profileIndex, userId, strongBox = false)
    }

    private fun generateKey(profileIndex: Int, userId: String, strongBox: Boolean) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias(profileIndex, userId),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        generator.init(builder.build())
        generator.generateKey()
    }

    private fun createCipher(profileIndex: Int, userId: String): Cipher {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(alias(profileIndex, userId), null)
            ?: throw KeyStoreException("Biometric key is unavailable")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    private fun deleteKey(profileIndex: Int, userId: String) {
        if (userId.isBlank()) return
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply {
                load(null)
                if (containsAlias(alias(profileIndex, userId))) {
                    deleteEntry(alias(profileIndex, userId))
                }
            }
        }
    }

    private fun deleteLegacyKey() {
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply {
                load(null)
                if (containsAlias(LEGACY_ALIAS)) deleteEntry(LEGACY_ALIAS)
            }
        }
    }

    private fun alias(profileIndex: Int, userId: String): String =
        KEY_PREFIX + ProfilePinCrypto.sha256Hex(
            "primary-profile-biometric:$profileIndex:$userId",
        )
}
