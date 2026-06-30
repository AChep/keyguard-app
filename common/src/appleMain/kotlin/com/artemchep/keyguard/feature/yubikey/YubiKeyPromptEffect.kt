package com.artemchep.keyguard.feature.yubikey

import androidx.compose.runtime.Composable
import arrow.core.left
import com.artemchep.keyguard.common.model.PureYubiKeyAuthPrompt
import com.artemchep.keyguard.common.model.YubiKeyAuthPrompt
import com.artemchep.keyguard.ui.CollectedEffect
import kotlinx.coroutines.flow.Flow

@Composable
actual fun YubiKeyPromptEffect(
    flow: Flow<PureYubiKeyAuthPrompt>,
) {
    CollectedEffect(flow) { event ->
        when (event) {
            is YubiKeyAuthPrompt -> event.onComplete(
                UnsupportedOperationException("YubiKey is not supported on iOS yet.").left(),
            )
        }
    }
}
