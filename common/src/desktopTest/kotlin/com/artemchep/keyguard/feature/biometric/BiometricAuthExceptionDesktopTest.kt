package com.artemchep.keyguard.feature.biometric

import com.artemchep.autotype.BiometricsException
import com.artemchep.autotype.BiometricsStatus
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_HW_UNAVAILABLE
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_KEY_INVALIDATED
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_LOCKOUT
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_POLICY_NOT_INSTALLED
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_USER_CANCELED
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class BiometricAuthExceptionDesktopTest {
    @Test
    fun `lost linux credential preserves enrollment for password rearm`() = runTest {
        val source = BiometricsException(BiometricsStatus.CREDENTIAL_NOT_FOUND, "Credential missing")
        val platform = Platform.Desktop.Linux(isFlatpak = false)

        assertEquals(ERROR_HW_UNAVAILABLE, source.toBiometricAuthException(platform).code)
        val localized = source.toBiometricAuthException(LeContext(), platform)
        assertEquals(ERROR_HW_UNAVAILABLE, localized.code)
        assertEquals(source.message, localized.message)
    }

    @Test
    fun `lost persistent desktop credentials still invalidate enrollment`() {
        val source = BiometricsException(BiometricsStatus.CREDENTIAL_NOT_FOUND, "Credential missing")
        for (platform in listOf(Platform.Desktop.Windows, Platform.Desktop.MacOS.Jvm)) {
            assertEquals(ERROR_KEY_INVALIDATED, source.toBiometricAuthException(platform).code)
        }
    }

    @Test
    fun `cancelled policy installation remains a cancellation`() = runTest {
        val source = BiometricsException(BiometricsStatus.USER_CANCELED, "Installation cancelled")
        val result = source.toBiometricAuthException(
            context = LeContext(),
            platform = Platform.Desktop.Linux(isFlatpak = false),
        )

        assertEquals(ERROR_USER_CANCELED, result.code)
        assertEquals(source.message, result.message)
    }

    @Test
    fun `security device locked maps to biometric lockout`() {
        val source = BiometricsException(
            status = BiometricsStatus.SECURITY_DEVICE_LOCKED,
            message = "Touch ID is locked.",
        )

        val result = source.toBiometricAuthException()

        assertEquals(ERROR_LOCKOUT, result.code)
        assertEquals(source.message, result.message)
    }

    @Test
    fun `missing polkit policy maps to policy not installed`() {
        val source = BiometricsException(
            status = BiometricsStatus.POLICY_NOT_INSTALLED,
            message = "/usr/share/polkit-1/actions/x.policy is not installed",
        )

        val result = source.toBiometricAuthException()

        assertEquals(ERROR_POLICY_NOT_INSTALLED, result.code)
        assertEquals(source.message, result.message)
    }

    @Test
    fun `missing polkit policy on flatpak explains the host command`() = runTest {
        val source = BiometricsException(
            status = BiometricsStatus.POLICY_NOT_INSTALLED,
            message = "native message",
        )

        val result = source.toBiometricAuthException(
            context = LeContext(),
            platform = Platform.Desktop.Linux(isFlatpak = true),
        )

        assertEquals(ERROR_POLICY_NOT_INSTALLED, result.code)
        assertTrue(result.message.orEmpty().contains(FLATPAK_INSTALL_POLICY_COMMAND))
    }

    @Test
    fun `missing polkit policy on native linux keeps the command out`() = runTest {
        val source = BiometricsException(
            status = BiometricsStatus.POLICY_NOT_INSTALLED,
            message = "native message",
        )

        val result = source.toBiometricAuthException(
            context = LeContext(),
            platform = Platform.Desktop.Linux(isFlatpak = false),
        )

        assertEquals(ERROR_POLICY_NOT_INSTALLED, result.code)
        assertTrue(result.message.orEmpty().isNotEmpty())
        assertTrue(!result.message.orEmpty().contains("flatpak run"))
        assertTrue(result.message.orEmpty().contains(source.message.orEmpty()))
    }

    @Test
    fun `policy installation failures retain their diagnostic`() = runTest {
        for (message in listOf(
            "Failed to start pkexec: No such file or directory",
            "Not authorized to install the polkit policy",
            "Installing the polkit policy failed with code 1: Read-only file system",
            "polkit has not loaded the policy yet",
        )) {
            val result = BiometricsException(BiometricsStatus.POLICY_NOT_INSTALLED, message)
                .toBiometricAuthException(LeContext(), Platform.Desktop.Linux(isFlatpak = false))

            assertEquals(ERROR_POLICY_NOT_INSTALLED, result.code)
            assertTrue(result.message.orEmpty().endsWith(message))
        }
    }
}
