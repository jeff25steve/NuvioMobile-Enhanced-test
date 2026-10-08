package com.nuvio.app.features.profiles

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.readBytes
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
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
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
                    return value?.toUtf8String()
                }
                migrateLegacyPayload(profileIndex)
            }
        } finally {
            // The query itself is released by copyMatching.
        }
    }

    actual fun savePayload(profileIndex: Int, payload: String) {
        val data = payload.toNSData()

        val query = createQuery(profileIndex)
        try {
            CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
            val bridgedData = CFBridgingRetain(data)
            try {
                CFDictionaryAddValue(query, kSecValueData, bridgedData)
                deleteExisting(profileIndex)
                SecItemAdd(query, null)
            } finally {
                bridgedData?.let { CFRelease(it) }
            }
        } finally {
            CFRelease(query)
        }
    }

    actual fun removePayload(profileIndex: Int) {
        deleteExisting(profileIndex)
        platform.Foundation.NSUserDefaults.standardUserDefaults
            .removeObjectForKey("${LEGACY_PREFIX}${profileIndex}")
    }

    private fun migrateLegacyPayload(profileIndex: Int): String? {
        val legacyKey = "${LEGACY_PREFIX}${profileIndex}"
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
        val data = payload.toNSData()

        val query = createQuery(profileIndex)
        return try {
            CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
            val bridgedData = CFBridgingRetain(data)
            try {
                CFDictionaryAddValue(query, kSecValueData, bridgedData)
                deleteExisting(profileIndex)
                SecItemAdd(query, null) == errSecSuccess
            } finally {
                bridgedData?.let { CFRelease(it) }
            }
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
            addString(it, kSecAttrAccount, "profile-${profileIndex}")
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

    private fun String.toNSData(): NSData =
        encodeToByteArray().let { bytes ->
            if (bytes.isEmpty()) {
                NSData.create(bytes = null, length = 0uL)
            } else {
                bytes.usePinned { pinned ->
                    NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                }
            }
        }

    private fun NSData.toUtf8String(): String? {
        val size = length.toInt()
        if (size == 0) return ""
        val pointer = bytes ?: return null
        return pointer.readBytes(size).decodeToString()
    }

    private fun addString(
        query: CFMutableDictionaryRef,
        key: CFStringRef?,
        value: String,
    ) {
        val retained = CFBridgingRetain(value as NSString)
        CFDictionaryAddValue(query, key, retained)
        retained?.let { CFRelease(it) }
    }
}
