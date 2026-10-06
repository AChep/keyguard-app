package com.artemchep.keyguard.feature.fido2

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.ui.CollectedEffect
import kotlinx.coroutines.flow.Flow

/** Native Apple UI hosts prompts outside Compose. */
@Composable
actual fun Fido2PromptEffect(flow: Flow<Fido2Prompt>) {
    CollectedEffect(flow) { it.cancel() }
}
