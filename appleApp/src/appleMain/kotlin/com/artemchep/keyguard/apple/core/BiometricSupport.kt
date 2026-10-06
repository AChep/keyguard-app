package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.common.model.BiometricAuthException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.compose.resources.getString
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.unlock_biometric_auth_failed
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

/**
 * Shows the system biometrics (Touch ID / Face ID) sheet and suspends until it resolves.
 * Returns `null` on success, otherwise the failure mapped onto the shared
 * [BiometricAuthException] codes (user cancellation maps to codes the shared
 * producers silently ignore).
 */
internal suspend fun evaluateBiometrics(
    reason: String,
    authenticated: suspend (LAContext) -> Unit = {},
): BiometricAuthException? {
    val context = LAContext()
    val fallbackMessage = getString(Res.string.unlock_biometric_auth_failed)
    try {
        val failure = suspendCancellableCoroutine<BiometricAuthException?> { continuation ->
            continuation.invokeOnCancellation { context.invalidate() }
            context.evaluatePolicy(
                LAPolicyDeviceOwnerAuthenticationWithBiometrics,
                localizedReason = reason,
            ) { success, error ->
                val exception = if (success) {
                    null
                } else {
                    val code = when (error?.code) {
                        LAErrorUserCancel -> BiometricAuthException.ERROR_USER_CANCELED
                        LAErrorSystemCancel,
                        LAErrorAppCancel -> BiometricAuthException.ERROR_CANCELED
                        LAErrorUserFallback -> BiometricAuthException.ERROR_NEGATIVE_BUTTON
                        LAErrorBiometryLockout -> BiometricAuthException.ERROR_LOCKOUT
                        LAErrorBiometryNotEnrolled -> BiometricAuthException.ERROR_NO_BIOMETRICS
                        LAErrorBiometryNotAvailable -> BiometricAuthException.ERROR_HW_UNAVAILABLE
                        else -> BiometricAuthException.ERROR_UNKNOWN
                    }
                    val message = error?.localizedDescription
                        ?: fallbackMessage
                    BiometricAuthException(code, message)
                }
                continuation.resume(exception)
            }
        }
        if (failure == null) {
            currentCoroutineContext().ensureActive()
            authenticated(context)
        }
        return failure
    } finally {
        context.invalidate()
    }
}
