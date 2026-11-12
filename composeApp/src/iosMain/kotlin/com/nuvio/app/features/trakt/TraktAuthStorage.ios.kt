package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSUserDefaults
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecDuplicateItem
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual object TraktAuthStorage {
    private const val SERVICE = "com.nuvio.media.trakt"
    private const val PAYLOAD_KEY = "trakt_auth_payload"

    actual fun loadPayload(profileId: Int): String? {
        val (status, payload) = readKeychain(profileId)
        return when (status) {
            errSecSuccess -> {
                if (payload != null) removeLegacyPayload(profileId)
                payload
            }
            errSecItemNotFound -> migrateLegacyPayload(profileId)
            // errSecInteractionNotAllowed means the item exists but is unavailable while
            // locked. Never mistake that for a missing item or fall back to plaintext.
            else -> null
        }
    }

    actual fun savePayload(profileId: Int, payload: String) {
        writeKeychainPayload(profileId, payload)
        removeLegacyPayload(profileId)
    }

    actual fun removeProfile(profileId: Int) {
        val status = deleteExisting(profileId)
        check(status == errSecSuccess || status == errSecItemNotFound) {
            "Unable to remove protected Trakt credentials (OSStatus $status)"
        }
        // Keep the legacy copy if Keychain refused deletion (for example, before
        // the first unlock) so a later unlocked launch can retry cleanup.
        removeLegacyPayload(profileId)
    }

    private fun migrateLegacyPayload(profileId: Int): String? {
        val legacy = NSUserDefaults.standardUserDefaults.stringForKey(legacyKey(profileId))
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return try {
            writeKeychainPayload(profileId, legacy)
            val (status, migrated) = readKeychain(profileId)
            if (status != errSecSuccess || migrated != legacy) return null
            removeLegacyPayload(profileId)
            legacy
        } catch (_: Exception) {
            // Keep the legacy copy until the Keychain write is confirmed. A later unlocked
            // launch can retry migration without losing the existing Trakt session.
            null
        }
    }

    private fun readKeychain(profileId: Int): Pair<Int, String?> {
        val query = createQuery(profileId)
        return try {
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                result.value = null
                val status = SecItemCopyMatching(query, result.ptr)
                val data: CFDataRef? = if (status == errSecSuccess) {
                    result.value?.reinterpret()
                } else {
                    result.value?.let { CFRelease(it) }
                    null
                }
                val payload = data?.let { item ->
                    try {
                        val length = CFDataGetLength(item).toInt()
                        if (length <= 0) {
                            return@let null
                        }
                        // CFDataGetBytePtr returns an opaque byte pointer; readBytes is the
                        // Kotlin/Native-supported path into a ByteArray (avoids ByteVar index
                        // type issues that failed iOS Debug compile on recovery run).
                        val ptr = CFDataGetBytePtr(item) ?: return@let null
                        ptr.readBytes(length).decodeToString()
                    } finally {
                        CFRelease(item)
                    }
                }
                status to payload
            }
        } finally {
            CFRelease(query)
        }
    }

    private fun writeKeychainPayload(profileId: Int, payload: String) {
        require(payload.isNotBlank())
        val query = createQuery(profileId)
        val bytes = payload.encodeToByteArray().toUByteArray()
        val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong())
            ?: error("Unable to encode Trakt credentials")
        val attributes = dictionary()
        try {
            CFDictionarySetValue(attributes, kSecValueData, data)
            val update = SecItemUpdate(query, attributes)
            if (update == errSecItemNotFound) {
                CFDictionarySetValue(query, kSecValueData, data)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                val added = SecItemAdd(query, null)
                if (added == errSecDuplicateItem) {
                    // Another caller may have inserted the same profile item after our
                    // update lookup. Retry as an update instead of failing the migration.
                    val retriedUpdate = SecItemUpdate(query, attributes)
                    check(retriedUpdate == errSecSuccess) {
                        "Unable to update protected Trakt credentials (OSStatus $retriedUpdate)"
                    }
                } else {
                    check(added == errSecSuccess) {
                        "Unable to save protected Trakt credentials (OSStatus $added)"
                    }
                }
            } else {
                check(update == errSecSuccess) {
                    "Unable to update protected Trakt credentials (OSStatus $update)"
                }
            }
        } finally {
            CFRelease(attributes)
            CFRelease(data)
            CFRelease(query)
        }
    }

    private fun deleteExisting(profileId: Int): Int {
        val query = createQuery(profileId)
        return try {
            SecItemDelete(query)
        } finally {
            CFRelease(query)
        }
    }

    private fun createQuery(profileId: Int): CFMutableDictionaryRef =
        dictionary().also { query ->
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            addString(query, kSecAttrService, SERVICE)
            addString(query, kSecAttrAccount, "profile-$profileId")
        }

    private fun dictionary(): CFMutableDictionaryRef =
        CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            5,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: error("Unable to create Keychain query")

    private fun addString(query: CFMutableDictionaryRef, key: CFStringRef?, value: String) {
        val retained = CFBridgingRetain(value) ?: return
        try {
            CFDictionarySetValue(query, key, retained)
        } finally {
            CFRelease(retained)
        }
    }

    private fun legacyKey(profileId: Int): String = ProfileScopedKey.of(PAYLOAD_KEY, profileId)

    private fun removeLegacyPayload(profileId: Int) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(legacyKey(profileId))
    }
}
