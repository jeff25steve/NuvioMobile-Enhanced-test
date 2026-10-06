package com.nuvio.app.features.profiles

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.CoreFoundation.CFDictionary
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
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
        val query = baseQuery(profileIndex) + mapOf(kSecReturnData to true)
        val (status, value) = copyMatching(query)
        if (status == errSecSuccess) {
            return (value as? NSData)?.let { data ->
                NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
            }
        }

        val legacyKey = "${LEGACY_PREFIX}${profileIndex}"
        val legacy = platform.Foundation.NSUserDefaults.standardUserDefaults
            .stringForKey(legacyKey)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (savePayloadInternal(profileIndex, legacy)) {
            platform.Foundation.NSUserDefaults.standardUserDefaults.removeObjectForKey(legacyKey)
        }
        return legacy
    }

    actual fun savePayload(profileIndex: Int, payload: String) {
        savePayloadInternal(profileIndex, payload)
    }

    actual fun removePayload(profileIndex: Int) {
        SecItemDelete(baseQuery(profileIndex) as CFDictionary)
        platform.Foundation.NSUserDefaults.standardUserDefaults
            .removeObjectForKey("${LEGACY_PREFIX}${profileIndex}")
    }

    private fun savePayloadInternal(profileIndex: Int, payload: String): Boolean {
        val data = NSString.create(string = payload)
            .dataUsingEncoding(NSUTF8StringEncoding)
            ?: return false

        val query = baseQuery(profileIndex)
        SecItemDelete(query as CFDictionary)
        val addQuery = query + mapOf(
            kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecValueData to data,
        )
        return SecItemAdd(addQuery as CFDictionary, null) == errSecSuccess
    }

    private fun baseQuery(profileIndex: Int): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to SERVICE,
        kSecAttrAccount to "profile-${profileIndex}",
    )

    private fun copyMatching(query: Map<Any?, Any?>): Pair<Int, CFTypeRef?> {
        return memScoped {
            val result = alloc<ObjCObjectVar<CFTypeRef?>>()
            val status = SecItemCopyMatching(query as CFDictionary, result.ptr)
            status to result.value
        }
    }
}
