package com.artemchep.keyguard.feature.fido2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.util.fido2.Fido2Exception
import com.artemchep.keyguard.util.fido2.Fido2Failure
import com.artemchep.keyguard.util.fido2.NativeFido2Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun Fido2PromptEffect(flow: Flow<Fido2Prompt>) {
    var pending by remember { mutableStateOf<Fido2Prompt?>(null) }
    CollectedEffect(flow) { prompt ->
        pending?.cancel()
        pending = prompt
    }
    val prompt = pending ?: return
    val active by prompt.active.collectAsState()
    if (active) NativeFido2Dialog(prompt)
}

@Suppress("TooGenericExceptionCaught") // Finish the prompt for native bridge errors.
@Composable
private fun NativeFido2Dialog(prompt: Fido2Prompt) {
    var pin by remember(prompt) { mutableStateOf("") }
    var submittedPin by remember(prompt) { mutableStateOf<String?>(null) }
    var requestPin by remember(prompt) { mutableStateOf(false) }
    var invalidPin by remember(prompt) { mutableStateOf(false) }
    var attempt by remember(prompt) { mutableIntStateOf(0) }
    DisposableEffect(prompt) { onDispose { prompt.cancel() } }
    LaunchedEffect(prompt, attempt) {
        try {
            val result = NativeFido2Client().execute(prompt.operation, submittedPin)
            prompt.complete(Result.success(result))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Fido2Exception) {
            if (
                error.failure == Fido2Failure.PIN_REQUIRED ||
                    error.failure == Fido2Failure.INVALID_PIN
            ) {
                invalidPin = error.failure == Fido2Failure.INVALID_PIN
                requestPin = true
            } else prompt.complete(Result.failure(error))
        } catch (error: Exception) {
            prompt.complete(Result.failure(error))
        } finally {
            submittedPin = null
        }
    }
    AlertDialog(
        onDismissRequest = prompt::cancel,
        title = { Text(stringResource(Res.string.fido2_unlock_title)) },
        text = { Fido2PromptContent(requestPin, invalidPin, pin) { pin = it } },
        confirmButton = {
            if (requestPin)
                TextButton(
                    enabled = pin.isNotEmpty(),
                    onClick = {
                        submittedPin = pin
                        pin = ""
                        requestPin = false
                        attempt++
                    },
                ) {
                    Text(stringResource(Res.string.continue_))
                }
        },
        dismissButton = {
            TextButton(onClick = prompt::cancel) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
private fun Fido2PromptContent(
    requestPin: Boolean,
    invalidPin: Boolean,
    pin: String,
    onPinChange: (String) -> Unit,
) {
    Column {
        Text(
            stringResource(
                if (requestPin) Res.string.fido2_pin_prompt else Res.string.fido2_touch_prompt
            )
        )
        if (requestPin)
            OutlinedTextField(
                value = pin,
                onValueChange = onPinChange,
                label = { Text(stringResource(Res.string.fido2_pin_label)) },
                isError = invalidPin,
                supportingText =
                    if (invalidPin) ({ Text(stringResource(Res.string.fido2_error_invalid_pin)) })
                    else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
            )
    }
}
