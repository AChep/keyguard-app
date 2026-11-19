package com.artemchep.keyguard.feature.fido2

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.artemchep.keyguard.feature.dialog.focusOnDialogOpen
import com.artemchep.keyguard.platform.requireSecurityKeyAccess
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.PasswordKeyboardHint
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
    if (active) NativeFido2Dialog(prompt) { pin ->
        requireSecurityKeyAccess(Res.string.fido2_error_title)
        NativeFido2Client().execute(prompt.operation, pin)
    }
}

@Suppress("TooGenericExceptionCaught") // Finish the prompt for native bridge errors.
@Composable
internal fun NativeFido2Dialog(prompt: Fido2Prompt, execute: suspend (String?) -> ByteArray) {
    var pin by remember(prompt) { mutableStateOf("") }
    var submittedPin by remember(prompt) { mutableStateOf<String?>(null) }
    var requestPin by remember(prompt) { mutableStateOf(false) }
    var invalidPin by remember(prompt) { mutableStateOf(false) }
    var attempt by remember(prompt) { mutableIntStateOf(0) }
    DisposableEffect(prompt) { onDispose { prompt.cancel() } }
    LaunchedEffect(prompt, attempt) {
        try {
            val result = execute(submittedPin)
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
    fun submitPin() {
        if (requestPin && pin.isNotEmpty()) {
            submittedPin = pin
            pin = ""
            requestPin = false
            attempt++
        }
    }
    AlertDialog(
        onDismissRequest = prompt::cancel,
        title = { Text(stringResource(Res.string.fido2_prompt_title)) },
        text = { Fido2PromptContent(requestPin, invalidPin, pin, { pin = it }, ::submitPin) },
        confirmButton = {
            if (requestPin)
                TextButton(
                    enabled = pin.isNotEmpty(),
                    onClick = ::submitPin,
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
    onSubmit: () -> Unit,
) {
    Column {
        Text(
            stringResource(
                if (requestPin) Res.string.fido2_pin_prompt else Res.string.fido2_touch_prompt
            )
        )
        if (requestPin) Fido2PinField(invalidPin, pin, onPinChange, onSubmit)
    }
}

@Composable
private fun Fido2PinField(
    invalidPin: Boolean,
    pin: String,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    OutlinedTextField(
        modifier = Modifier.focusOnDialogOpen(),
        value = pin,
        onValueChange = onPinChange,
        label = { Text(stringResource(Res.string.fido2_pin_label)) },
        // Unlike a trailing icon, a suffix takes no space while empty.
        suffix = { PasswordKeyboardHint(focused) },
        isError = invalidPin,
        supportingText =
            if (invalidPin) ({ Text(stringResource(Res.string.fido2_error_invalid_pin)) })
            else null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        singleLine = true,
        interactionSource = interactionSource,
    )
}
