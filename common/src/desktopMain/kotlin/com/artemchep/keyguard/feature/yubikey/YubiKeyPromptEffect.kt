package com.artemchep.keyguard.feature.yubikey

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.PureYubiKeyAuthPrompt
import kotlinx.coroutines.flow.Flow

@Composable
actual fun YubiKeyPromptEffect(
    flow: Flow<PureYubiKeyAuthPrompt>,
) = Unit
