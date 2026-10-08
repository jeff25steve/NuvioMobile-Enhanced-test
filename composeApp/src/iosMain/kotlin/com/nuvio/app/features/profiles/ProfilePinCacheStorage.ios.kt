package com.nuvio.app.features.profiles

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDataRefVar
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSUserDefaults
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
        return copyMatching(query).second
            ?: migrateLegacyPayload(profileIndex)
    }

    actual fun savePayload(profileIndex: Int, payload: String) {
        val data = payload.toCFData() ?: return
        val query = createQuery(profileIndex)

        try {
            CFDictionarySetValue(
                query,
                kSecAttrAccessible,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            )
            CFDictionarySetValue(query, kSecValueData, data)
            deleteExisting(profileIndex)
            SecItemAdd(query, null)
        } finally {
            CFRelease(data)
            CFRelease(query)
        }
    }

    actual fun removePayload(profileIndex: Int) {
        deleteExisting(profileIndex)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(
            "${LEGACY_PREFIX}${profileIndex}",
        )
    }

    private fun migrateLegacyPayload(profileIndex: Int): String? {
        val legacyKey = "${LEGACY_PREFIX}${profileIndex}"
        val legacy = NSUserDefaults.standardUserDefaults
            .stringForKey(legacyKey)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        if (savePayloadInternal(profileIndex, legacy)) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(legacyKey)
            return legacy
        }

        NSUserDefaults.standardUserDefaults.removeObjectForKey(legacyKey)
        return null
    }

    private fun savePayloadInternal(profileIndex: Int, payload: String): Boolean {
        val data = payload.toCFData() ?: return false
        val query = createQuery(profileIndex)

        return try {
            CFDictionarySetValue(
                query,
                kSecAttrAccessible,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            )
            CFDictionarySetValue(query, kSecValueData, data)
            deleteExisting(profileIndex)
            SecItemAdd(query, null) == errSecSuccess
        } finally {
            CFRelease(data)
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
            5,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!.also {
            CFDictionarySetValue(it, kSecClass, kSecClassGenericPassword)
            addString(it, kSecAttrService, SERVICE)
            addString(it, kSecAttrAccount, "profile-${profileIndex}")
        }
    }

    private fun copyMatching(
        query: CFMutableDictionaryRef,
    ): Pair<Int, String?> {
        return memScoped {
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            val result = alloc<CFDataRefVar>()
            result.value = null
            val status = SecItemCopyMatching(query, result.ptr.reinterpret())

            val payload = if (status == errSecSuccess) {
                result.value?.let { data ->
                    try {
                        data.readBytes().decodeToString()
                    } finally {
                        CFRelease(data)
                    }
                }
            } else {
                result.value?.let { CFRelease(it) }
                null
            }

            try {
                status to payload
            } finally {
                CFRelease(query)
            }
        }
    }

    private fun addString(
        query: CFMutableDictionaryRef,
        key: CFStringRef?,
        value: String,
    ) {
        val stringRef = cfString(value) ?: return
        CFDictionarySetValue(query, key, stringRef)
        CFRelease(stringRef)
    }

    private fun cfString(value: String): CFStringRef? =
        memScoped {
            CFStringCreateWithCString(
                kCFAllocatorDefault,
                value.cstr.ptr,
                kCFStringEncodingUTF8,
            )
        }

    private fun String.toCFData(): CFDataRef? {
        val bytes = encodeToByteArray()
        return if (bytes.isEmpty()) {
            CFDataCreate(kCFAllocatorDefault, null, 0)
        } else {
            bytes.usePinned { pinned ->
                CFDataCreate(
                    kCFAllocatorDefault,
                    pinned.addressOf(0).reinterpret(),
                    bytes.size.convert(),
                )
            }
        }
    }
}
