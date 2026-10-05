package com.nuvio.app.features.profiles

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
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
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.darwin.NSObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object ProfileBiometricAuth {
    private const val SERVICE = "com.nuvio.media.profile-biometric"
    private const val ACCOUNT = "primary"

    private var initialized = false

    actual fun initialize(host: Any) {
        initialized = host is platform.UIKit.UIViewController
    }

    actual fun isAvailable(): Boolean {
        if (!initialized) return false
        val context = LAContext()
        memScoped {
            val error = alloc<ObjCObjectVar<platform.Foundation.NSError?>>()
            return context.canEvaluatePolicy(
                LAPolicy.deviceOwnerAuthenticationWithBiometrics,
                error.ptr,
            )
        }
    }

    actual fun isConfigured(profileIndex: Int): Boolean {
        if (profileIndex != 1) return false
        var item: CFTypeRef? = null
        val context = LAContext().apply { interactionNotAllowed = true }
        val query = baseQuery() + mapOf(
            platform.Security.kSecReturnAttributes to true,
            platform.Security.kSecUseAuthenticationContext to context,
        )
        val status = SecItemCopyMatching(query as platform.CoreFoundation.CFDictionary, kotlinx.cinterop.CValuesRefPointerVar { })
        return status == errSecSuccess
    }

    actual suspend fun enable(profileIndex: Int): ProfileBiometricResult {
        if (profileIndex != 1 || !isAvailable()) return ProfileBiometricResult.Unavailable
        disable(profileIndex)

        val accessControl = createAccessControl() ?: return ProfileBiometricResult.Failed
        val addQuery = baseQuery() + mapOf(
            platform.Security.kSecAttrAccessControl to accessControl,
            platform.Security.kSecValueData to NSData.dataWithBytes(
                "nuvio-biometric-sentinel".encodeToByteArray(),
                "nuvio-biometric-sentinel".encodeToByteArray().size.toULong(),
            ),
        )

        val addStatus = SecItemAdd(addQuery as platform.CoreFoundation.CFDictionary, null)
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
            ) { success, _ ->
                if (!continuation.isActive) return@evaluatePolicy
                continuation.resume(
                    if (success) {
                        ProfileBiometricResult.Success
                    } else {
                        ProfileBiometricResult.Cancelled
                    },
                )
            }
        }

        if (biometricResult != ProfileBiometricResult.Success) return biometricResult

        var item: CFTypeRef? = null
        val query = baseQuery() + mapOf(
            platform.Security.kSecReturnData to true,
            platform.Security.kSecUseAuthenticationContext to context,
        )
        val status = SecItemCopyMatching(query as platform.CoreFoundation.CFDictionary, kotlinx.cinterop.CValuesRefPointerVar { })
        if (status == errSecSuccess) return ProfileBiometricResult.Success

        disable(profileIndex)
        return ProfileBiometricResult.Invalidated
    }

    actual fun disable(profileIndex: Int) {
        if (profileIndex != 1) return
        SecItemDelete(baseQuery() as platform.CoreFoundation.CFDictionary)
    }

    private fun baseQuery(): Map<platform.CoreFoundation.CFStringRef, Any> = mapOf(
        platform.Security.kSecClass to platform.Security.kSecClassGenericPassword,
        platform.Security.kSecAttrService to SERVICE,
        platform.Security.kSecAttrAccount to ACCOUNT,
    )

    private fun createAccessControl(): SecAccessControl? {
        return memScoped {
            val error = alloc<ObjCObjectVar<platform.Foundation.NSError?>>()
            SecAccessControlCreateWithFlags(
                null,
                platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                SecAccessControlCreateFlags.biometryCurrentSet,
                error.ptr,
            )
        }
    }
}
