package com.nuvio.app.features.profiles

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.CoreFoundation.CFDictionary
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.NSError
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
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
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.getString

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object ProfileBiometricAuth {
    private const val SERVICE = "com.nuvio.media.profile-biometric"
    private const val ACCOUNT = "primary"
    private const val SENTINEL = "nuvio-biometric-SENTINEL"
    private const val LEGACY_SERVICE = SERVICE
    private const val LEGACY_ACCOUNT = ACCOUNT

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

    actual suspend fun isConfigured(profileIndex: Int, userId: String): Boolean {
        if (profileIndex != 1 || userId.isBlank()) return false
        val context = LAContext().apply { interactionNotAllowed = true }
        val query = baseQuery(userId) + mapOf(
            kSecReturnAttributes to true,
            kSecUseAuthenticationContext to context,
        )
        val status = copyMatching(query).first
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    actual suspend fun enable(profileIndex: Int, userId: String): ProfileBiometricResult {
        if (profileIndex != 1 || userId.isBlank() || !isAvailable()) {
            return ProfileBiometricResult.Unavailable
        }

        disable(profileIndex, userId)
        deleteLegacyCredential()
        val accessControl = createAccessControl() ?: return ProfileBiometricResult.Failed
        val valueData = NSString.create(string = SENTINEL)
            .dataUsingEncoding(NSUTF8StringEncoding)
            ?: return ProfileBiometricResult.Failed
        val addQuery = baseQuery(userId) + mapOf(
            kSecAttrAccessControl to accessControl,
            kSecValueData to valueData,
        )

        val addStatus = withContext(Dispatchers.Default) {
            SecItemAdd(addQuery as CFDictionary, null)
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

    actual suspend fun authenticate(profileIndex: Int, userId: String): ProfileBiometricResult {
        return authenticateInternal(profileIndex, userId, setup = false)
    }

    actual fun disable(profileIndex: Int, userId: String) {
        if (profileIndex != 1) return
        if (userId.isNotBlank()) {
            SecItemDelete(baseQuery(userId) as CFDictionary)
        }
        deleteLegacyCredential()
    }

    private suspend fun authenticateInternal(
        profileIndex: Int,
        userId: String,
        setup: Boolean,
    ): ProfileBiometricResult {
        if (profileIndex != 1 || userId.isBlank()) return ProfileBiometricResult.Unavailable
        if (!isConfigured(profileIndex, userId)) return ProfileBiometricResult.NotConfigured
        if (!isAvailable()) return ProfileBiometricResult.Unavailable

        val context = LAContext().apply {
            localizedReason = getString(
                if (setup) {
                    Res.string.profile_biometric_setup_reason
                } else {
                    Res.string.profile_biometric_unlock_reason
                },
            )
            if (!setup) {
                localizedFallbackTitle = getString(Res.string.profile_biometric_prompt_use_pin)
            }
        }
        val authenticatedQuery = baseQuery(userId) + mapOf(
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
                disable(profileIndex, userId)
                ProfileBiometricResult.Invalidated
            }
            errSecUserCanceled -> ProfileBiometricResult.Cancelled
            else -> ProfileBiometricResult.Failed
        }
    }

    private fun baseQuery(userId: String): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to SERVICE,
        kSecAttrAccount to account(userId),
    )

    private fun account(userId: String): String =
        "primary-" + ProfilePinCrypto.sha256Hex("primary-profile-biometric:$userId")

    private fun deleteLegacyCredential() {
        SecItemDelete(
            mapOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to LEGACY_SERVICE,
                kSecAttrAccount to LEGACY_ACCOUNT,
            ) as CFDictionary,
        )
    }

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
