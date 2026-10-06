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
import platform.Foundation.NSError
import platform.Foundation.NSData
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicy
import platform.Security.SecAccessControl
import platform.Security.SecAccessControlCreateFlags
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecDuplicateItem
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecUserCanceled
import platform.Security.errSecSuccess
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

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object ProfileBiometricAuth {
    private const val SERVICE = "com.nuvio.media.profile-biometric"
    private const val ACCOUNT = "primary"
    private val sentinel = "nuvio-biometric-sentinel".encodeToByteArray()

    private var initialized = false

    actual fun initialize(host: Any) {
        initialized = host is platform.UIKit.UIViewController
    }

    actual fun isAvailable(): Boolean {
        if (!initialized) return false
        val context = LAContext()
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            context.canEvaluatePolicy(
                LAPolicy.deviceOwnerAuthenticationWithBiometrics,
                error.ptr,
            )
        }
    }

    actual suspend fun isConfigured(profileIndex: Int): Boolean {
        if (profileIndex != 1) return false
        val context = LAContext().apply { interactionNotAllowed = true }
        val query = baseQuery() + mapOf(
            kSecReturnAttributes to true,
            kSecUseAuthenticationContext to context,
        )
        val status = copyMatching(query).first
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    actual suspend fun enable(profileIndex: Int): ProfileBiometricResult {
        if (profileIndex != 1 || !isAvailable()) return ProfileBiometricResult.Unavailable

        disable(profileIndex)
        val accessControl = createAccessControl() ?: return ProfileBiometricResult.Failed
        val addQuery = baseQuery() + mapOf(
            kSecAttrAccessControl to accessControl,
            kSecValueData to NSData.create(bytes = allocArrayOf(sentinel), length = sentinel.size.toULong()),
        )

        val addStatus = withContext(Dispatchers.Default) {
            SecItemAdd(addQuery as CFDictionary, null)
        }
        if (addStatus != errSecSuccess && addStatus != errSecDuplicateItem) {
            return ProfileBiometricResult.Failed
        }

        val result = authenticateInternal(profileIndex, setup = true)
        if (result != ProfileBiometricResult.Success) {
            disable(profileIndex)
        }
        return result
    }

    actual suspend fun authenticate(profileIndex: Int): ProfileBiometricResult {
        return authenticateInternal(profileIndex, setup = false)
    }

    private suspend fun authenticateInternal(
        profileIndex: Int,
        setup: Boolean,
    ): ProfileBiometricResult {
        if (profileIndex != 1) return ProfileBiometricResult.Unavailable
        if (!isConfigured(profileIndex)) return ProfileBiometricResult.NotConfigured
        if (!isAvailable()) return ProfileBiometricResult.Unavailable

        val context = LAContext().apply {
            localizedReason = if (setup) {
                "Confirm your biometric to enable primary Nuvio profile unlock."
            } else {
                "Use Face ID or Touch ID to unlock your primary Nuvio profile."
            }
            if (!setup) {
                localizedFallbackTitle = "Use PIN"
            }
        }
        val authenticatedQuery = baseQuery() + mapOf(
            kSecReturnData to true,
            kSecUseAuthenticationContext to context,
        )

        // Keychain access control is the authority. The protected item cannot be returned unless
        // the system's biometric ACL is satisfied. This avoids trusting an evaluatePolicy boolean.
        return when (withContext(Dispatchers.Default) {
            copyMatching(authenticatedQuery).first
        }) {
            errSecSuccess -> ProfileBiometricResult.Success
            errSecItemNotFound -> {
                disable(profileIndex)
                ProfileBiometricResult.Invalidated
            }
            errSecUserCanceled -> ProfileBiometricResult.Cancelled
            else -> ProfileBiometricResult.Failed
        }
    }

    actual suspend fun disable(profileIndex: Int) {
        if (profileIndex != 1) return
        withContext(Dispatchers.Default) {
            SecItemDelete(baseQuery() as CFDictionary)
        }
    }

    private fun baseQuery(): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to SERVICE,
        kSecAttrAccount to ACCOUNT,
    )

    private fun createAccessControl(): SecAccessControl? {
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            SecAccessControlCreateWithFlags(
                null,
                kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                SecAccessControlCreateFlags.biometryCurrentSet,
                error.ptr,
            )
        }
    }

    private fun copyMatching(query: Map<Any?, Any?>): Pair<Int, CFTypeRef?> {
        return memScoped {
            val result = alloc<ObjCObjectVar<CFTypeRef?>>()
            val status = SecItemCopyMatching(query as CFDictionary, result.ptr)
            status to result.value
        }
    }
}
