package com.nuvio.app.features.profiles

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSError
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecDuplicateItem
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecUserCanceled
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecValueData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object ProfileBiometricAuth {
    private const val SERVICE = "com.nuvio.media.profile-biometric"
    private const val ACCOUNT = "primary"
    private const val SENTINEL = "nuvio-biometric-sentinel"
    private const val LEGACY_SERVICE = SERVICE
    private const val LEGACY_ACCOUNT = ACCOUNT

    private var initialized = false

    actual fun initialize(host: Any) {
        initialized = host is UIViewController
        deleteLegacyCredential()
    }

    actual fun isAvailable(): Boolean {
        if (!initialized) return false

        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            LAContext().canEvaluatePolicy(
                LAPolicyDeviceOwnerAuthenticationWithBiometrics,
                error.ptr,
            )
        }
    }

    actual suspend fun isConfigured(profileIndex: Int, userId: String): Boolean {
        if (profileIndex != 1 || userId.isBlank()) return false

        val context = LAContext().apply {
            interactionNotAllowed = true
        }

        val status = withContext(Dispatchers.Default) {
            withKeychainQuery(userId) { query ->
                CFDictionaryAddValue(query, kSecReturnAttributes, kCFBooleanTrue)
                setAuthenticationContext(query, context)
                SecItemCopyMatching(query, null)
            }
        }

        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    actual suspend fun enable(profileIndex: Int, userId: String): ProfileBiometricResult {
        if (profileIndex != 1 || userId.isBlank()) {
            return ProfileBiometricResult.Unavailable
        }
        if (!isAvailable()) {
            return ProfileBiometricResult.Unavailable
        }

        disable(profileIndex, userId)
        deleteLegacyCredential()

        val accessControl = createAccessControl()
            ?: return ProfileBiometricResult.Failed

        val valueData = SENTINEL.encodeToByteArray().toNSData()

        val addStatus = withContext(Dispatchers.Default) {
            withKeychainQuery(userId) { query ->
                CFDictionaryAddValue(query, kSecAttrAccessControl, accessControl)
                val bridgedValueData = CFBridgingRetain(valueData)
                try {
                    CFDictionaryAddValue(query, kSecValueData, bridgedValueData)
                    SecItemAdd(query, null)
                } finally {
                    bridgedValueData?.let { CFRelease(it) }
                }
            }
        }

        CFRelease(accessControl)

        if (addStatus != errSecSuccess && addStatus != errSecDuplicateItem) {
            return ProfileBiometricResult.Failed
        }

        val result = authenticateInternal(profileIndex, userId, setup = true)
        if (result != ProfileBiometricResult.Success) {
            disable(profileIndex, userId)
        }
        return result
    }

    actual suspend fun authenticate(
        profileIndex: Int,
        userId: String,
    ): ProfileBiometricResult =
        authenticateInternal(profileIndex, userId, setup = false)

    actual fun disable(profileIndex: Int, userId: String) {
        if (profileIndex != 1) return

        if (userId.isNotBlank()) {
            withKeychainQuery(userId) { query ->
                SecItemDelete(query)
            }
        }
        deleteLegacyCredential()
    }

    private suspend fun authenticateInternal(
        profileIndex: Int,
        userId: String,
        setup: Boolean,
    ): ProfileBiometricResult {
        if (profileIndex != 1 || userId.isBlank()) {
            return ProfileBiometricResult.Unavailable
        }
        if (!isConfigured(profileIndex, userId)) {
            return ProfileBiometricResult.NotConfigured
        }
        if (!isAvailable()) {
            return ProfileBiometricResult.Unavailable
        }

        val context = LAContext().apply {
            localizedReason = if (setup) {
                "Confirm your fingerprint or face to turn on biometric unlock."
            } else {
                "Use your fingerprint or face to unlock your primary profile."
            }
            if (!setup) {
                localizedFallbackTitle = "Use PIN"
            }
        }

        val status = withContext(Dispatchers.Default) {
            withKeychainQuery(userId) { query ->
                CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
                setAuthenticationContext(query, context)

                memScoped {
                    val result = alloc<CFTypeRefVar>()
                    result.value = null
                    val resultStatus = SecItemCopyMatching(query, result.ptr)
                    result.value?.let { CFRelease(it) }
                    resultStatus
                }
            }
        }

        return when (status) {
            errSecSuccess -> ProfileBiometricResult.Success
            errSecItemNotFound -> {
                disable(profileIndex, userId)
                ProfileBiometricResult.Invalidated
            }
            errSecUserCanceled -> ProfileBiometricResult.Cancelled
            errSecInteractionNotAllowed -> ProfileBiometricResult.Failed
            else -> ProfileBiometricResult.Failed
        }
    }

    private fun createAccessControl() =
        memScoped {
            val error = alloc<CFErrorRefVar>()
            SecAccessControlCreateWithFlags(
                kCFAllocatorDefault,
                kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                kSecAccessControlBiometryCurrentSet,
                error.ptr,
            )
        }

    private fun setAuthenticationContext(
        query: CFMutableDictionaryRef,
        context: LAContext,
    ) {
        val contextReference = CFBridgingRetain(context)
        CFDictionaryAddValue(
            query,
            kSecUseAuthenticationContext,
            contextReference,
        )
        contextReference?.let { CFRelease(it) }
    }

    private fun deleteLegacyCredential() {
        withKeychainQuery(
            userId = null,
            service = LEGACY_SERVICE,
            account = LEGACY_ACCOUNT,
        ) { query ->
            SecItemDelete(query)
        }
    }

    private fun <T> withKeychainQuery(
        userId: String?,
        service: String = SERVICE,
        account: String? = userId?.let(::account),
        block: (CFMutableDictionaryRef) -> T,
    ): T {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: error("Unable to allocate Keychain query")

        try {
            addString(query, kSecAttrService, service)
            if (account != null) {
                addString(query, kSecAttrAccount, account)
            }
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            return block(query)
        } finally {
            CFRelease(query)
        }
    }

    private fun String.toNSData(): NSData =
        encodeToByteArray().let { bytes ->
            if (bytes.isEmpty()) {
                NSData()
            } else {
                bytes.usePinned { pinned ->
                    NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                }
            }
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

    private fun account(userId: String): String =
        "primary-" + ProfilePinCrypto.sha256Hex(
            "primary-profile-biometric:$userId",
        )
}
