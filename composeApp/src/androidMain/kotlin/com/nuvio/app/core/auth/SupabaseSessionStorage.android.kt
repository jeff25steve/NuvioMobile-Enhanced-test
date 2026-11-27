package com.nuvio.app.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun createNativeSupabaseSessionStorage(): SupabaseSessionStorage =
    AndroidSupabaseSessionStorage

/**
 * AES-256-GCM session encryption with a non-exportable Android Keystore key.
 *
 * Ciphertext is stored in a private, app-local preferences file. The key itself never leaves
 * Android Keystore. The legacy preferences file name matches multiplatform-settings 1.3.0.
 */
internal object AndroidSupabaseSessionStorage : SupabaseSessionStorage {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "com.nuvio.media.supabase.session.v1"
    private const val SECURE_PREFERENCES = "nuvio_secure_supabase_sessions"
    private const val FORMAT_VERSION = "v1"
    private const val GCM_TAG_BITS = 128

    private val keyLock = Any()

    @Volatile
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    override suspend fun readEncrypted(key: String): String? = withContext(Dispatchers.IO) {
        val stored = securePreferences().getString(key, null) ?: return@withContext null
        val parts = stored.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == FORMAT_VERSION) {
            "Unsupported or corrupt secure Supabase session record"
        }

        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        require(iv.size == 12 && ciphertext.isNotEmpty()) {
            "Corrupt secure Supabase session record"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(key.encodeToByteArray())
        cipher.doFinal(ciphertext).decodeToString()
    }

    override suspend fun writeEncrypted(key: String, value: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(key.encodeToByteArray())
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        val encoded = listOf(
            FORMAT_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        ).joinToString(":")

        check(securePreferences().edit().putString(key, encoded).commit()) {
            "Unable to persist encrypted Supabase session"
        }
    }

    override suspend fun deleteEncrypted(key: String) = withContext(Dispatchers.IO) {
        check(securePreferences().edit().remove(key).commit()) {
            "Unable to delete encrypted Supabase session"
        }
    }

    override suspend fun readLegacy(key: String): String? = withContext(Dispatchers.IO) {
        legacyPreferences().getString(key, null)
    }

    override suspend fun deleteLegacy(key: String) = withContext(Dispatchers.IO) {
        val preferences = legacyPreferences()
        if (preferences.contains(key)) {
            check(preferences.edit().remove(key).commit()) {
                "Unable to remove legacy Supabase session"
            }
        }
    }

    private fun securePreferences(): SharedPreferences =
        requireContext().getSharedPreferences(SECURE_PREFERENCES, Context.MODE_PRIVATE)

    private fun legacyPreferences(): SharedPreferences {
        val context = requireContext()
        // This is the exact default file name used by multiplatform-settings-no-arg 1.3.0.
        return context.getSharedPreferences(
            "${context.packageName}_preferences",
            Context.MODE_PRIVATE,
        )
    }

    private fun requireContext(): Context =
        checkNotNull(applicationContext) {
            "Supabase session storage has not been initialized with an application context"
        }

    private fun existingKey(): SecretKey = synchronized(keyLock) {
        val keyStore = loadKeyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
            ?: error("Encrypted Supabase session exists but its Android Keystore key is missing")
    }

    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        val keyStore = loadKeyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
}
