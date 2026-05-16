package com.artemchep.keyguard.feature.biometric

import androidx.compose.runtime.Composable
import arrow.core.left
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricAuthException.Companion.ERROR_UNKNOWN
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.BiometricAuthPromptSimple
import com.artemchep.keyguard.common.model.PureBiometricAuthPrompt
import com.artemchep.keyguard.ui.CollectedEffect
import kotlinx.coroutines.flow.Flow

@Composable
actual fun BiometricPromptEffect(
    flow: Flow<PureBiometricAuthPrompt>,
) {
    CollectedEffect(flow) { event ->
        val exception = BiometricAuthException(
            code = ERROR_UNKNOWN,
            message = "Biometric authentication is not supported on iOS yet.",
        )
        when (event) {
            is BiometricAuthPrompt -> event.onComplete(exception.left())
            is BiometricAuthPromptSimple -> event.onComplete(exception.left())
        }
    }
}
