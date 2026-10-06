package com.nuvio.app.features.profiles

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.KeyPermanentlyInvalidatedException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.KeyProperties

actual object ProfileBiometricAuth {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_PREFIX = "nuvio_primary_profile_biometric_"
    private val sentinel = "nuvio-profile-biometric".encodeToByteArray()

    private var activity: FragmentActivity? = null

    actual fun initialize(host: Any) {
        activity = host as? FragmentActivity
    }

    actual fun isAvailable(): Boolean {
        val host = activity ?: return false
        return BiometricManager.from(host).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    actual suspend fun isConfigured(profileIndex: Int): Boolean {
        if (profileIndex != 1) return false
        return runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            keyStore.containsAlias(alias(profileIndex))
        }.getOrDefault(false)
    }

    actual suspend fun enable(profileIndex: Int): ProfileBiometricResult {
        if (profileIndex != 1 || !isAvailable()) return ProfileBiometricResult.Unavailable

        deleteKey(profileIndex)
        return runCatching {
            generateKey(profileIndex)
            when (val result = authenticateInternal(profileIndex)) {
                ProfileBiometricResult.Success -> result
                else -> {
                    deleteKey(profileIndex)
                    result
                }
            }
        }.getOrElse {
            deleteKey(profileIndex)
            ProfileBiometricResult.Failed
        }
    }

    actual suspend fun authenticate(profileIndex: Int): ProfileBiometricResult {
        if (profileIndex != 1) return ProfileBiometricResult.Unavailable
        if (!isConfigured(profileIndex)) return ProfileBiometricResult.NotConfigured

        return runCatching {
            authenticateInternal(profileIndex)
        }.getOrElse { error ->
            if (error is KeyPermanentlyInvalidatedException) {
                deleteKey(profileIndex)
                ProfileBiometricResult.Invalidated
            } else {
                ProfileBiometricResult.Failed
            }
        }
    }

    actual suspend fun disable(profileIndex: Int) {
        deleteKey(profileIndex)
    }

    private suspend fun authenticateInternal(profileIndex: Int): ProfileBiometricResult {
        val host = activity ?: return ProfileBiometricResult.Unavailable
        val biometricManager = BiometricManager.from(host)
        if (
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
                BiometricManager.BIOMETRIC_SUCCESS
        ) {
            return ProfileBiometricResult.Unavailable
        }

        val cipher = createCipher(profileIndex)
        val cryptoObject = BiometricPrompt.CryptoObject(cipher)

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
                        if (authenticatedCipher == null) {
                            if (continuation.isActive) continuation.resume(ProfileBiometricResult.Failed)
                            return
                        }

                        val outcome = runCatching {
                            authenticatedCipher.doFinal(sentinel)
                            ProfileBiometricResult.Success
                        }.getOrElse { error ->
                            if (error is KeyPermanentlyInvalidatedException) {
                                deleteKey(profileIndex)
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
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON -> ProfileBiometricResult.FallbackRequested
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_CANCELED -> ProfileBiometricResult.Cancelled
                                BiometricPrompt.ERROR_NO_BIOMETRICS,
                                BiometricPrompt.ERROR_HW_NOT_PRESENT,
                                BiometricPrompt.ERROR_HW_UNAVAILABLE,
                                BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> ProfileBiometricResult.Unavailable
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
                .setTitle("Unlock primary profile")
                .setSubtitle("Use your fingerprint or other strong biometric")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use PIN")
                .setConfirmationRequired(false)
                .build()

            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(promptInfo, cryptoObject)
        }
    }

    private fun generateKey(profileIndex: Int) {
        runCatching {
            generateKey(profileIndex, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        }.onFailure { error ->
            if (error !is StrongBoxUnavailableException) throw error
            generateKey(profileIndex, strongBox = false)
        }
    }

    private fun generateKey(profileIndex: Int, strongBox: Boolean) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias(profileIndex),
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

    private fun createCipher(profileIndex: Int): Cipher {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(alias(profileIndex), null)
            ?: throw KeyStoreException("Biometric key is unavailable")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    private fun deleteKey(profileIndex: Int) {
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply {
                load(null)
                if (containsAlias(alias(profileIndex))) deleteEntry(alias(profileIndex))
            }
        }
    }

    private fun alias(profileIndex: Int): String = KEY_PREFIX + profileIndex
}
