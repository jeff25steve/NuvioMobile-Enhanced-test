package com.nuvio.app.features.profiles

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFDataRefVar
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.NSError
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
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecUseAuthenticationUI
import platform.Security.kSecUseAuthenticationUIFail
import platform.Security.kSecValueData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        // Initialize is called from app setup and has no suspend boundary. Keep cleanup
        // off the UI thread without blocking it; account-scoped cleanup remains explicit
        // in the repository when the account is known.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            deleteLegacyCredential()
        }
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

        try {
            val accessControl = createAccessControl()
                ?: return ProfileBiometricResult.Failed

            val valueData = SENTINEL.toNSData()

            val addStatus = try {
                withContext(Dispatchers.Default) {
                    withKeychainQuery(userId) { query ->
                        CFDictionarySetValue(query, kSecAttrAccessControl, accessControl)
                        val bridgedValueData = CFBridgingRetain(valueData)
                        try {
                            CFDictionarySetValue(query, kSecValueData, bridgedValueData)
                            SecItemAdd(query, null)
                        } finally {
                            bridgedValueData?.let { CFRelease(it) }
                        }
                    }
                }
            } finally {
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
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.Default) {
                disable(profileIndex, userId)
            }
            throw error
        }
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

        // SecItemCopyMatching is synchronous. Keep the authentication-triggering Keychain
        // operation off the Compose/UI thread so the app remains responsive while iOS presents
        // and waits for the biometric prompt.
        val status = withContext(Dispatchers.Default) {
            withKeychainQuery(userId) { query ->
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
        }

        return when (status) {
            errSecSuccess -> ProfileBiometricResult.Success
            errSecItemNotFound -> {
                withContext(Dispatchers.Default) {
                    disable(profileIndex, userId)
                }
                ProfileBiometricResult.Invalidated
            }
            errSecUserCanceled -> if (setup) {
                ProfileBiometricResult.Cancelled
            } else {
                ProfileBiometricResult.FallbackRequested
            }
            errSecInteractionNotAllowed -> ProfileBiometricResult.Failed
            else -> ProfileBiometricResult.Failed
        }
    }

    private fun createAccessControl() =
        SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            kSecAccessControlBiometryCurrentSet,
            null,
        )

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

        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            addString(query, kSecAttrService, service)
            if (account != null) addString(query, kSecAttrAccount, account)
            return block(query)
        } finally {
            CFRelease(query)
        }
    }

    private fun String.toNSData(): NSData =
        NSString.create(string = this).dataUsingEncoding(NSUTF8StringEncoding) ?: NSData()

    private fun addString(
        query: CFMutableDictionaryRef,
        key: CFStringRef?,
        value: String,
    ) {
        val retained = CFBridgingRetain(value) ?: return
        try {
            CFDictionarySetValue(query, key, retained)
        } finally {
            CFRelease(retained)
        }
    }

    private fun account(userId: String): String =
        "primary-" + ProfilePinCrypto.sha256Hex(
            "primary-profile-biometric:$userId",
        )
}
