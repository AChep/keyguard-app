package com.artemchep.keyguard.feature.biometric

import com.artemchep.autotype.BiometricsException
import com.artemchep.autotype.BiometricsStatus
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_HW_UNAVAILABLE
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_KEY_INVALIDATED
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_LOCKOUT
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_NEGATIVE_BUTTON
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_POLICY_NOT_INSTALLED
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_UNKNOWN
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_USER_CANCELED
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.biometric_error_policy_not_installed
import com.artemchep.keyguard.res.biometric_error_policy_not_installed_flatpak

/**
 * Command a Flatpak user runs on the host to install the polkit
 * policy that Keyguard ships inside the sandbox.
 */
internal const val FLATPAK_INSTALL_POLICY_COMMAND =
    "flatpak run --command=cat com.artemchep.keyguard " +
        "/app/share/polkit-1/actions/com.artemchep.keyguard.policy | " +
        "sudo install -D -m 0644 /dev/stdin " +
        "/usr/share/polkit-1/actions/com.artemchep.keyguard.policy"

/**
 * Converts a failure of the native desktop
 * biometrics into the common [BiometricAuthException].
 */
internal fun Throwable.toBiometricAuthException(
    platform: Platform = CurrentPlatform,
): BiometricAuthException {
    val code = when ((this as? BiometricsException)?.status) {
        BiometricsStatus.USER_CANCELED -> ERROR_USER_CANCELED
        BiometricsStatus.CREDENTIAL_NOT_FOUND -> if (platform is Platform.Desktop.Linux) {
            // The process-local credential can be restored by a password
            // unlock. Keep the saved binding so that re-arming remains enabled.
            ERROR_HW_UNAVAILABLE
        } else {
            ERROR_KEY_INVALIDATED
        }
        BiometricsStatus.SECURITY_DEVICE_LOCKED -> ERROR_LOCKOUT
        BiometricsStatus.UNAVAILABLE -> ERROR_HW_UNAVAILABLE
        BiometricsStatus.USER_PREFERS_PASSWORD -> ERROR_NEGATIVE_BUTTON
        BiometricsStatus.POLICY_NOT_INSTALLED -> ERROR_POLICY_NOT_INSTALLED
        BiometricsStatus.SUCCESS,
        BiometricsStatus.UNKNOWN,
        null,
            -> ERROR_UNKNOWN
    }
    return BiometricAuthException(code, message.orEmpty())
}

/**
 * Adds localized policy-install guidance while retaining native failure details.
 */
internal suspend fun Throwable.toBiometricAuthException(
    context: LeContext,
    platform: Platform = CurrentPlatform,
): BiometricAuthException {
    val exception = toBiometricAuthException(platform)
    if (exception.code != ERROR_POLICY_NOT_INSTALLED) {
        return exception
    }

    val isFlatpak = platform is Platform.Desktop.Linux && platform.isFlatpak
    val message = if (isFlatpak) {
        textResource(
            Res.string.biometric_error_policy_not_installed_flatpak,
            context,
            FLATPAK_INSTALL_POLICY_COMMAND,
        )
    } else {
        val guidance = textResource(
            Res.string.biometric_error_policy_not_installed,
            context,
        )
        listOfNotNull(guidance, exception.message?.takeIf { it.isNotBlank() })
            .joinToString("\n\n")
    }
    return BiometricAuthException(exception.code, message)
}
