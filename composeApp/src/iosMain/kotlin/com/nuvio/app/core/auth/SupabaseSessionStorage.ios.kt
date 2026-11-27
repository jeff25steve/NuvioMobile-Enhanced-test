package com.nuvio.app.core.auth

import kotlinx.cinterop.BetaInteropApi
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
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

internal actual fun createNativeSupabaseSessionStorage(): SupabaseSessionStorage =
    IosSupabaseSessionStorage

/**
 * Stores session JSON as a Keychain generic-password item.
 *
 * AfterFirstUnlockThisDeviceOnly allows Nuvio's background sync work to access the session after
 * the user has unlocked the device once since boot, while preventing migration to another device.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosSupabaseSessionStorage : SupabaseSessionStorage {
    private const val SERVICE = "com.nuvio.media.supabase.sessions"

    override suspend fun readEncrypted(key: String): String? {
        val query = createQuery(key)
        try {
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            return memScoped {
                val result = alloc<CFTypeRefVar>()
                result.value = null
                val status = SecItemCopyMatching(query, result.ptr)
                if (status != errSecSuccess) {
                    result.value?.let { CFRelease(it) }
                    if (status == errSecItemNotFound) return@memScoped null
                    error("Unable to read protected Supabase session (OSStatus $status)")
                }

                val data: CFDataRef = result.value?.reinterpret()
                    ?: error("Keychain returned an empty Supabase session item")
                try {
                    val length = CFDataGetLength(data).toInt()
                    require(length > 0) { "Keychain returned an empty Supabase session item" }
                    val bytes = CFDataGetBytePtr(data)
                        ?: error("Unable to access protected Supabase session bytes")
                    bytes.readBytes(length).decodeToString()
                } finally {
                    CFRelease(data)
                }
            }
        } finally {
            CFRelease(query)
        }
    }

    override suspend fun writeEncrypted(key: String, value: String) {
        require(value.isNotBlank())
        val query = createQuery(key)
        val attributes = dictionary()
        val bytes = value.encodeToByteArray().toUByteArray()
        val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong())
            ?: error("Unable to encode Supabase session for Keychain")

        try {
            CFDictionarySetValue(attributes, kSecValueData, data)
            val updateStatus = SecItemUpdate(query, attributes)
            if (updateStatus == errSecItemNotFound) {
                CFDictionarySetValue(query, kSecValueData, data)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                val addStatus = SecItemAdd(query, null)
                if (addStatus == errSecDuplicateItem) {
                    // A concurrent writer inserted this account after our update lookup.
                    val retryStatus = SecItemUpdate(query, attributes)
                    check(retryStatus == errSecSuccess) {
                        "Unable to update protected Supabase session (OSStatus $retryStatus)"
                    }
                } else {
                    check(addStatus == errSecSuccess) {
                        "Unable to save protected Supabase session (OSStatus $addStatus)"
                    }
                }
            } else {
                check(updateStatus == errSecSuccess) {
                    "Unable to update protected Supabase session (OSStatus $updateStatus)"
                }
            }
        } finally {
            CFRelease(data)
            CFRelease(attributes)
            CFRelease(query)
        }
    }

    override suspend fun readLegacy(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(key)

    override suspend fun deleteLegacy(key: String) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(key) != null) {
            defaults.removeObjectForKey(key)
        }
    }

    private fun createQuery(key: String): CFMutableDictionaryRef =
        dictionary().also { query ->
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            addString(query, kSecAttrService, SERVICE)
            addString(query, kSecAttrAccount, key)
        }

    private fun dictionary(): CFMutableDictionaryRef =
        CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            5,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: error("Unable to create Keychain query")

    private fun addString(query: CFMutableDictionaryRef, key: CFStringRef?, value: String) {
        val retained = CFBridgingRetain(value) ?: error("Unable to encode Keychain query value")
        try {
            CFDictionarySetValue(query, key, retained)
        } finally {
            CFRelease(retained)
        }
    }
}
