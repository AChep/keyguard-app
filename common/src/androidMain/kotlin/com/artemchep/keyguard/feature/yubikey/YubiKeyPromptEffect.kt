package com.artemchep.keyguard.feature.yubikey

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.model.PureYubiKeyAuthPrompt
import com.artemchep.keyguard.common.model.YubiKeyAuthPrompt
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.util.yubikey.YubiKeyOperation
import com.artemchep.keyguard.util.yubikey.YubiKeyResult
import kotlinx.coroutines.flow.Flow

@Composable
actual fun YubiKeyPromptEffect(flow: Flow<PureYubiKeyAuthPrompt>) {
    val state = remember { mutableStateOf<YubiKeyAuthPrompt?>(null) }
    val launcher = rememberLauncherForActivityResult(remember { YubiKeyActivityContract() }) { result ->
        val prompt = state.value
        state.value = null
        if (prompt != null) {
            prompt.onComplete(result.mapCatching { (it as YubiKeyResult.Response).bytes }.fold(
                onSuccess = { it.right() },
                onFailure = { it.asYubiKeyAppException(prompt.slot).left() },
            ))
        }
    }
    CollectedEffect(flow) { event ->
        val prompt = event as? YubiKeyAuthPrompt ?: return@CollectedEffect
        state.value = prompt
        launcher.launch(YubiKeyOperation.ChallengeResponse(prompt.slot, prompt.challenge))
    }
}
