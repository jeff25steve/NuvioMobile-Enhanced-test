package com.nuvio.app.features.trakt

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nuvio.app.core.storage.ProfileScopedKey
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual object TraktAuthStorage {
    private const val preferencesName = "nuvio_trakt_auth"
    private const val payloadKey = "trakt_auth_payload"
    private const val keyAlias = "com.nuvio.media.trakt.credentials.v1"
    private const val transformation = "AES/GCM/NoPadding"
    private const val gcmTagBits = 128

    private var preferences: SharedPreferences? = null
    private val lock = Any()

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(profileId: Int): String? = synchronized(lock) {
        val prefs = preferences ?: return@synchronized null
        val key = ProfileScopedKey.of(payloadKey, profileId)
        val stored = prefs.getString(key, null) ?: return@synchronized null
        try {
            decrypt(stored, key)
        } catch (_: Exception) {
            // Existing releases stored this JSON payload in plaintext. Replace it in place,
            // and only expose it as migrated after the encrypted preference commit succeeds.
            if (!stored.trimStart().startsWith("{")) return@synchronized null
            val encrypted = try {
                encrypt(stored, key)
            } catch (_: Exception) {
                return@synchronized null
            }
            if (prefs.edit().putString(key, encrypted).commit()) stored else null
        }
    }

    actual fun savePayload(profileId: Int, payload: String) = synchronized(lock) {
        val prefs = preferences ?: return@synchronized
        val key = ProfileScopedKey.of(payloadKey, profileId)
        val encrypted = encrypt(payload, key)
        if (!prefs.edit().putString(key, encrypted).commit()) {
            throw IOException("Unable to save protected Trakt credentials")
        }
    }

    actual fun removeProfile(profileId: Int) = synchronized(lock) {
        val prefs = preferences ?: return@synchronized
        val key = ProfileScopedKey.of(payloadKey, profileId)
        if (!prefs.edit().remove(key).commit()) {
            throw IOException("Unable to remove protected Trakt credentials")
        }
    }

    private fun encrypt(value: String, preferenceKey: String): String {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD("$keyAlias:$preferenceKey".toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "${cipher.iv.toBase64()}.${ciphertext.toBase64()}"
    }

    private fun decrypt(value: String, preferenceKey: String): String {
        val separator = value.indexOf('.')
        require(separator > 0 && separator < value.lastIndex) { "Invalid encrypted Trakt credentials" }
        val iv = value.substring(0, separator).fromBase64()
        val ciphertext = value.substring(separator + 1).fromBase64()
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(gcmTagBits, iv))
        cipher.updateAAD("$keyAlias:$preferenceKey".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
