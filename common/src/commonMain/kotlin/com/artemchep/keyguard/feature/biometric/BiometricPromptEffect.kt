package com.artemchep.keyguard.feature.biometric

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.PureBiometricAuthPrompt
import kotlinx.coroutines.flow.Flow

@Composable
expect fun BiometricPromptEffect(
    flow: Flow<PureBiometricAuthPrompt>,
)
