package com.nuvio.app.features.profiles

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import platform.CoreFoundation.CFDictionary
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.NSError
import platform.Foundation.NSData
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAError
import platform.LocalAuthentication.LAPolicy
import platform.Security.SecAccessControl
import platform.Security.SecAccessControlCreateFlags
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

    actual fun isConfigured(profileIndex: Int): Boolean {
        if (profileIndex != 1) return false
        val context = LAContext().apply { interactionNotAllowed = true }
        val query = baseQuery() + mapOf(
            kSecReturnAttributes to true,
            kSecUseAuthenticationContext to context,
        )
        return copyMatching(query).first == errSecSuccess
    }

    actual suspend fun enable(profileIndex: Int): ProfileBiometricResult {
        if (profileIndex != 1 || !isAvailable()) return ProfileBiometricResult.Unavailable

        disable(profileIndex)
        val accessControl = createAccessControl() ?: return ProfileBiometricResult.Failed
        val addQuery = baseQuery() + mapOf(
            kSecAttrAccessControl to accessControl,
            kSecValueData to NSData.create(sentinel.refTo(0), sentinel.size.toULong()),
        )

        val addStatus = SecItemAdd(addQuery as CFDictionary, null)
        if (addStatus != errSecSuccess && addStatus != errSecDuplicateItem) {
            return ProfileBiometricResult.Failed
        }

        val result = authenticate(profileIndex)
        if (result != ProfileBiometricResult.Success) {
            disable(profileIndex)
        }
        return result
    }

    actual suspend fun authenticate(profileIndex: Int): ProfileBiometricResult {
        if (profileIndex != 1) return ProfileBiometricResult.Unavailable
        if (!isConfigured(profileIndex)) return ProfileBiometricResult.NotConfigured
        if (!isAvailable()) return ProfileBiometricResult.Unavailable

        val context = LAContext().apply {
            localizedFallbackTitle = "Use PIN"
        }

        val biometricResult = suspendCancellableCoroutine<ProfileBiometricResult> { continuation ->
            context.evaluatePolicy(
                LAPolicy.deviceOwnerAuthenticationWithBiometrics,
                "Use Face ID or Touch ID to unlock your primary profile.",
            ) { success, error ->
                if (!continuation.isActive) return@evaluatePolicy
                val code = (error as? NSError)?.code
                continuation.resume(
                    when {
                        success -> ProfileBiometricResult.Success
                        code == LAError.userFallback.code.toLong() -> ProfileBiometricResult.FallbackRequested
                        else -> ProfileBiometricResult.Cancelled
                    },
                )
            }
        }

        if (biometricResult != ProfileBiometricResult.Success) return biometricResult

        val authenticatedQuery = baseQuery() + mapOf(
            kSecReturnData to true,
            kSecUseAuthenticationContext to context,
        )
        val status = copyMatching(authenticatedQuery).first
        if (status == errSecSuccess) return ProfileBiometricResult.Success

        // The biometric policy succeeded, but the device's enrolled biometric set no longer
        // matches the access-controlled item. Treat this as an invalidated local credential and
        // require the Nuvio PIN to establish a new biometric credential.
        if (status == errSecItemNotFound) {
            disable(profileIndex)
            return ProfileBiometricResult.Invalidated
        }
        return ProfileBiometricResult.Failed
    }

    actual fun disable(profileIndex: Int) {
        if (profileIndex != 1) return
        SecItemDelete(baseQuery() as CFDictionary)
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
