package com.artemchep.keyguard.feature.yubikey

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.util.yubikey.YubiKeyOperation
import com.artemchep.keyguard.util.yubikey.YubiKeyResult
import kotlinx.coroutines.flow.Flow

@Composable
internal actual fun YubiKeyProvisionProbeEffect(flow: Flow<YubiKeyProvisionProbePrompt>) {
    val state = remember { mutableStateOf<YubiKeyProvisionProbePrompt?>(null) }
    val launcher = rememberLauncherForActivityResult(remember { YubiKeyActivityContract() }) { result ->
        val prompt = state.value
        state.value = null
        if (prompt != null) {
            prompt.onComplete(result.mapCatching {
                YubiKeyProvisionProbeResult(prompt.slot, (it as YubiKeyResult.SlotStatus).configured)
            }.fold(onSuccess = { it.right() }, onFailure = { it.asYubiKeyAppException(prompt.slot).left() }))
        }
    }
    CollectedEffect(flow) { prompt ->
        state.value = prompt
        launcher.launch(YubiKeyOperation.Inspect(prompt.slot))
    }
}

@Composable
internal actual fun YubiKeyProvisionEffect(flow: Flow<YubiKeyProvisionPrompt>) {
    val state = remember { mutableStateOf<YubiKeyProvisionPrompt?>(null) }
    val launcher = rememberLauncherForActivityResult(remember { YubiKeyActivityContract() }) { result ->
        val prompt = state.value
        state.value = null
        if (prompt != null) {
            prompt.secret.fill(0)
            prompt.onComplete(result.mapCatching { (it as YubiKeyResult.Response).bytes }.fold(
                onSuccess = { it.right() },
                onFailure = { it.asYubiKeyAppException(prompt.slot, provisioning = true).left() },
            ))
        }
    }
    CollectedEffect(flow) { prompt ->
        state.value = prompt
        launcher.launch(YubiKeyOperation.Provision(prompt.slot, prompt.challenge, prompt.secret, prompt.overwrite))
    }
}
