package com.artemchep.keyguard.feature.fido2

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

@Composable expect fun Fido2PromptEffect(flow: Flow<Fido2Prompt>)
