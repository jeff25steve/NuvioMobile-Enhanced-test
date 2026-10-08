package com.nuvio.app.features.profiles

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
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
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecAccessControlRef
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
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecUseAuthenticationUI
import platform.Security.kSecUseAuthenticationUIFail
import platform.Security.kSecValueData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object ProfileBiometricAuth {
    private const val SERVICE = "com.nuvio.media.profile-biometric"
    private const val LEGACY_SERVICE = SERVICE
    private const val LEGACY_ACCOUNT = "primary"
    private const val SENTINEL = "nuvio-biometric-sentinel"

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

        val status = withContext(Dispatchers.Default) {
            withKeychainQuery(userId) { query ->
                CFDictionarySetValue(query, kSecUseAuthenticationUI, kSecUseAuthenticationUIFail)
                CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
                SecItemCopyMatching(query, null)
            }
        }

        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    actual suspend fun enable(
        profileIndex: Int,
        userId: String,
    ): ProfileBiometricResult {
        if (profileIndex != 1 || userId.isBlank()) {
            return ProfileBiometricResult.Unavailable
        }
        if (!isAvailable()) {
            return ProfileBiometricResult.Unavailable
        }

        disable(profileIndex, userId)

        val accessControl = createAccessControl()
            ?: return ProfileBiometricResult.Failed

        val valueData = SENTINEL.toCFData()
            ?: return ProfileBiometricResult.Failed

        val addStatus = try {
            withContext(Dispatchers.Default) {
                withKeychainQuery(userId) { query ->
                    CFDictionarySetValue(query, kSecAttrAccessControl, accessControl)
                    CFDictionarySetValue(query, kSecValueData, valueData)
                    SecItemAdd(query, null)
                }
            }
        } finally {
            CFRelease(valueData)
            CFRelease(accessControl)
        }

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
            localizedFallbackTitle = ""
        }

        // Run the authentication-triggering Keychain read in the caller's context.
        // UI-driven callers execute here on the main dispatcher; moving this synchronous
        // system-authentication request to a worker dispatcher can interfere with prompt
        // presentation/lifecycle handling on iOS.
        val status = withKeychainQuery(userId) { query ->
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            setAuthenticationContext(query, context)

            memScoped {
                val result = alloc<CFDataRefVar>()
                result.value = null
                val resultStatus = SecItemCopyMatching(query, result.ptr.reinterpret())
                result.value?.let { CFRelease(it) }
                resultStatus
            }
        }

        return when (status) {
            errSecSuccess -> ProfileBiometricResult.Success
            errSecItemNotFound -> {
                disable(profileIndex, userId)
                ProfileBiometricResult.Invalidated
            }
            errSecUserCanceled -> ProfileBiometricResult.FallbackRequested
            errSecInteractionNotAllowed -> ProfileBiometricResult.Failed
            else -> ProfileBiometricResult.Failed
        }
    }

    private fun createAccessControl(): SecAccessControlRef? =
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
        val contextReference = CFBridgingRetain(context) ?: return
        CFDictionarySetValue(query, kSecUseAuthenticationContext, contextReference)
        CFRelease(contextReference)
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
            8,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: error("Unable to allocate Keychain query")

        val serviceRef = cfString(service)
        val accountRef = account?.let(::cfString)
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, serviceRef)
            accountRef?.let { CFDictionarySetValue(query, kSecAttrAccount, it) }
            return block(query)
        } finally {
            accountRef?.let { CFRelease(it) }
            serviceRef?.let { CFRelease(it) }
            CFRelease(query)
        }
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
        return bytes.usePinned { pinned ->
            CFDataCreate(
                kCFAllocatorDefault,
                pinned.addressOf(0).reinterpret(),
                bytes.size.convert(),
            )
        }
    }

    private fun account(userId: String): String =
        "primary-" + ProfilePinCrypto.sha256Hex(
            "primary-profile-biometric:$userId",
        )
}
