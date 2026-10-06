package com.artemchep.keyguard.feature.yubikey

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.exception.YubiKeyAuthCanceledException
import com.artemchep.keyguard.common.model.PureYubiKeyAuthPrompt
import com.artemchep.keyguard.common.model.YubiKeyAuthPrompt
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.yubikey_usb_text
import com.artemchep.keyguard.res.yubikey_usb_touch_the_gold_sensor_note
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.util.yubikey.NativeYubiKeyClient
import com.artemchep.keyguard.util.yubikey.YubiKeyException
import com.artemchep.keyguard.util.yubikey.YubiKeyFailure
import com.artemchep.keyguard.util.yubikey.YubiKeyOperation
import com.artemchep.keyguard.util.yubikey.YubiKeyResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val DEVICE_POLL_INTERVAL_MS = 250L

@Composable
actual fun YubiKeyPromptEffect(flow: Flow<PureYubiKeyAuthPrompt>) {
    val requests = remember(flow) {
        flow.mapNotNull { event ->
            (event as? YubiKeyAuthPrompt)?.let { prompt ->
                NativePrompt(YubiKeyOperation.ChallengeResponse(prompt.slot, prompt.challenge)) { result ->
                    prompt.onComplete(result.map { (it as YubiKeyResult.Response).bytes })
                }
            }
        }
    }
    NativePromptEffect(requests)
}

@Composable
internal actual fun YubiKeyProvisionProbeEffect(flow: Flow<YubiKeyProvisionProbePrompt>) {
    val requests = remember(flow) {
        flow.map { prompt ->
            NativePrompt(YubiKeyOperation.Inspect(prompt.slot)) { result ->
                prompt.onComplete(result.map {
                    YubiKeyProvisionProbeResult(prompt.slot, (it as YubiKeyResult.SlotStatus).configured)
                })
            }
        }
    }
    NativePromptEffect(requests)
}

@Composable
internal actual fun YubiKeyProvisionEffect(flow: Flow<YubiKeyProvisionPrompt>) {
    val requests = remember(flow) {
        flow.map { prompt ->
            val operation = YubiKeyOperation.Provision(prompt.slot, prompt.challenge, prompt.secret, prompt.overwrite)
            NativePrompt(operation) { result ->
                prompt.secret.fill(0)
                prompt.onComplete(result.map { (it as YubiKeyResult.Response).bytes })
            }
        }
    }
    NativePromptEffect(requests)
}

private class NativePrompt(
    val operation: YubiKeyOperation,
    val onComplete: (Either<Throwable, YubiKeyResult>) -> Unit,
) {
    private var completed = false
    fun complete(result: Either<Throwable, YubiKeyResult>) {
        if (!completed) {
            completed = true
            onComplete(result)
        } else {
            result.getOrNull()?.let { (it as? YubiKeyResult.Response)?.bytes?.fill(0) }
        }
    }
}

@Suppress("TooGenericExceptionCaught") // Always finish the UI prompt on a native bridge failure.
@Composable
private fun NativePromptEffect(flow: Flow<NativePrompt>) {
    var pending by remember { mutableStateOf<NativePrompt?>(null) }
    CollectedEffect(flow) { prompt ->
        pending?.complete(YubiKeyAuthCanceledException().left())
        pending = prompt
    }
    val prompt = pending ?: return
    DisposableEffect(prompt) {
        onDispose { prompt.complete(YubiKeyAuthCanceledException().left()) }
    }
    LaunchedEffect(prompt) {
        val result = try {
            awaitYubiKey(prompt.operation).right()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.asYubiKeyAppException(
                prompt.operation.slot,
                provisioning = prompt.operation is YubiKeyOperation.Provision,
            ).left()
        }
        prompt.complete(result)
        if (pending === prompt) pending = null
    }
    AlertDialog(
        onDismissRequest = { pending = null },
        title = { Text(stringResource(Res.string.yubikey_usb_touch_the_gold_sensor_note)) },
        text = { Text(stringResource(Res.string.yubikey_usb_text)) },
        confirmButton = {
            TextButton(onClick = { pending = null }) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

private suspend fun awaitYubiKey(operation: YubiKeyOperation): YubiKeyResult {
    val client = NativeYubiKeyClient()
    val start = TimeSource.Monotonic.markNow()
    while (true) {
        try {
            return client.execute(operation)
        } catch (error: YubiKeyException) {
            if (error.failure != YubiKeyFailure.NO_DEVICE || start.elapsedNow() >= 30.seconds) throw error
            delay(DEVICE_POLL_INTERVAL_MS)
        }
    }
}
