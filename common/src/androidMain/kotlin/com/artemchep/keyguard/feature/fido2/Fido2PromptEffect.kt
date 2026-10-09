package com.artemchep.keyguard.feature.fido2

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.ui.CollectedEffect
import kotlinx.coroutines.flow.Flow

@Composable
actual fun Fido2PromptEffect(flow: Flow<Fido2Prompt>) {
    var pending by remember { mutableStateOf<Fido2Prompt?>(null) }
    val launcher =
        rememberLauncherForActivityResult(remember { Fido2ActivityContract() }) { result ->
            val prompt = pending
            pending = null
            if (prompt != null) prompt.complete(result) else result.getOrNull()?.fill(0)
        }
    DisposableEffect(flow) { onDispose { pending?.cancel() } }
    CollectedEffect(flow) { prompt ->
        // Activity results do not carry the prompt identity. Keep ownership until
        // the old activity returns, even if its coroutine has already cancelled.
        if (pending != null) {
            prompt.cancel()
        } else {
            pending = prompt
            launcher.launch(prompt.operation)
        }
    }
}
