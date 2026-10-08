package com.nuvio.app.features.profiles

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.create
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object ProfilePinCacheStorage {
    private const val SERVICE = "com.nuvio.media.profile-pin-cache"
    private const val LEGACY_PREFIX = "profile_pin_cache_"

    actual fun loadPayload(profileIndex: Int): String? {
        val query = createQuery(profileIndex)
        return try {
            copyMatching(query).let { (status, value) ->
                if (status == errSecSuccess) {
                    return (CFBridgingRelease(value) as? platform.Foundation.NSData)?.let { data ->
                        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
                    }
                }
                migrateLegacyPayload(profileIndex)
            }
        } finally {
            // The query itself is released by copyMatching.
        }
    }

    actual fun savePayload(profileIndex: Int, payload: String) {
        val data = NSString.create(string = payload)
            .dataUsingEncoding(NSUTF8StringEncoding)
            ?: return

        val query = createQuery(profileIndex)
        try {
            CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
            CFDictionaryAddValue(query, kSecValueData, data)
            deleteExisting(profileIndex)
            SecItemAdd(query, null)
        } finally {
            CFRelease(query)
        }
    }

    actual fun removePayload(profileIndex: Int) {
        deleteExisting(profileIndex)
        platform.Foundation.NSUserDefaults.standardUserDefaults
            .removeObjectForKey("\${LEGACY_PREFIX}\${profileIndex}")
    }

    private fun migrateLegacyPayload(profileIndex: Int): String? {
        val legacyKey = "\${LEGACY_PREFIX}\${profileIndex}"
        val legacy = platform.Foundation.NSUserDefaults.standardUserDefaults
            .stringForKey(legacyKey)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        if (savePayloadInternal(profileIndex, legacy)) {
            platform.Foundation.NSUserDefaults.standardUserDefaults
                .removeObjectForKey(legacyKey)
            return legacy
        }

        platform.Foundation.NSUserDefaults.standardUserDefaults
            .removeObjectForKey(legacyKey)
        return null
    }

    private fun savePayloadInternal(profileIndex: Int, payload: String): Boolean {
        val data = NSString.create(string = payload)
            .dataUsingEncoding(NSUTF8StringEncoding)
            ?: return false

        val query = createQuery(profileIndex)
        return try {
            CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
            CFDictionaryAddValue(query, kSecValueData, data)
            deleteExisting(profileIndex)
            SecItemAdd(query, null) == errSecSuccess
        } finally {
            CFRelease(query)
        }
    }

    private fun deleteExisting(profileIndex: Int) {
        val query = createQuery(profileIndex)
        try {
            SecItemDelete(query)
        } finally {
            CFRelease(query)
        }
    }

    private fun createQuery(profileIndex: Int): CFMutableDictionaryRef {
        return CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!.also {
            CFDictionaryAddValue(it, kSecClass, kSecClassGenericPassword)
            addString(it, kSecAttrService, SERVICE)
            addString(it, kSecAttrAccount, "profile-\${profileIndex}")
        }
    }

    private fun copyMatching(
        query: CFMutableDictionaryRef,
    ): Pair<Int, NSData?> {
        return try {
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                result.value = null
                val status = SecItemCopyMatching(query, result.ptr)
                val data = if (status == errSecSuccess) {
                    CFBridgingRelease(result.value) as? NSData
                } else {
                    result.value?.let { CFRelease(it) }
                    null
                }
                status to data
            }
        } finally {
            CFRelease(query)
        }
    }

    private fun addString(
        query: CFMutableDictionaryRef,
        key: CFStringRef?,
        value: String,
    ) {
        val retained = CFBridgingRetain(NSString.create(string = value))
        CFDictionaryAddValue(query, key, retained)
        CFRelease(retained)
    }
}
