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
import platform.LocalAuthentication.LAAccessControlOperationUseItem
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicy
import kotlin.coroutines.resume
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
    private const val SENTINEL = "nuvio-biometric-sentinel"
    private const val LEGACY_SERVICE = SERVICE
    private const val LEGACY_ACCOUNT = ACCOUNT

    private var initialized = false

    actual fun initialize(host: Any) {
        initialized = host is platform.UIKit.UIViewController
        // Remove the pre-account-bound credential so a rollback cannot reuse it across accounts.
        deleteLegacyCredential()
    }

    actual fun isAvailable(): Boolean {
        if (!initialized) return false
        val context = LAContext()
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val biometricsAvailable = context.canEvaluatePolicy(
                LAPolicy.deviceOwnerAuthenticationWithBiometrics,
                error.ptr,
            )
            if (!biometricsAvailable) return@memScoped false
            context.canEvaluatePolicy(
                LAPolicy.deviceOwnerAuthentication,
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
        val status = withContext(Dispatchers.Default) {
            copyMatching(query).first
        }
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    actual suspend fun enable(profileIndex: Int, userId: String): ProfileBiometricResult {
        if (profileIndex != 1 || userId.isBlank() || !isAvailable()) {
            return ProfileBiometricResult.Unavailable
        }

        withContext(Dispatchers.Default) {
            disable(profileIndex, userId)
            deleteLegacyCredential()
        }
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

        val localizedReason = getString(
            if (setup) {
                Res.string.profile_biometric_setup_reason
            } else {
                Res.string.profile_biometric_unlock_reason
            },
        )
        val accessControl = createAccessControl()
            ?: return ProfileBiometricResult.Failed
        val context = LAContext().apply {
            this.localizedReason = localizedReason
            // Setup should require biometrics without exposing a device-passcode fallback.
            // During unlock, this button deliberately means "Use PIN" at the app level.
            localizedFallbackTitle = if (setup) {
                ""
            } else {
                getString(Res.string.profile_biometric_prompt_use_pin)
            }
        }

        // Evaluate the exact same access-control policy used by the Keychain item so cancellation
        // and the app-level PIN fallback are explicit. The Keychain read below remains the final
        // authority and uses this same LAContext.
        val (authorized, authenticationError) =
            suspendCancellableCoroutine<Pair<Boolean, NSError?>> { continuation ->
                context.evaluateAccessControl(
                    accessControl = accessControl,
                    operation = LAAccessControlOperationUseItem,
                    localizedReason = localizedReason,
                ) { success, error ->
                    if (continuation.isActive) {
                        continuation.resume(success to error)
                    }
                }
                continuation.invokeOnCancellation { context.invalidate() }
            }

        if (!authorized) {
            return when (authenticationError?.code?.toInt()) {
                LAErrorUserFallback -> ProfileBiometricResult.FallbackRequested
                LAErrorUserCancel,
                LAErrorAppCancel,
                LAErrorSystemCancel,
                -> ProfileBiometricResult.Cancelled
                LAErrorBiometryNotAvailable,
                LAErrorBiometryNotEnrolled,
                LAErrorBiometryLockout,
                LAErrorPasscodeNotSet,
                -> ProfileBiometricResult.Unavailable
                else -> ProfileBiometricResult.Failed
            }
        }

        val authenticatedQuery = baseQuery(userId) + mapOf(
            kSecReturnData to true,
            kSecUseAuthenticationContext to context,
        )

        // Keychain access control remains the authority: policy success alone is never treated as
        // proof that this exact protected item can be read.
        return when (withContext(Dispatchers.Default) {
            copyMatching(authenticatedQuery).first
        }) {
            errSecSuccess -> ProfileBiometricResult.Success
            errSecItemNotFound -> {
                withContext(Dispatchers.Default) {
                    disable(profileIndex, userId)
                }
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
