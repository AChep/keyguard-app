package com.artemchep.keyguard.ipctestclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpField
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.toKeyIdHex

@Composable
fun OpenPgpForm(controller: DriverController) {
    val state = controller.pgpForm
    val onChange: (OpenPgpFormState) -> Unit = { controller.pgpForm = it }
    OperationSection(state, onChange)
    if (OpenPgpField.PAYLOAD in state.operation.fields) {
        PayloadSection(controller, state, onChange)
    }
    if (state.operation.fields.any { it in RECIPIENT_FIELDS }) {
        RecipientSection(state, onChange)
    }
    KeySection(controller, state, onChange)
    OptionSection(state, onChange)
    ViolationSection(state, onChange)
    Button(
        onClick = controller::runOpenPgp,
        enabled = !controller.busy,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(if (controller.busy) "Running…" else "Send ${state.operation.label}")
    }
}

@Composable
private fun OperationSection(
    state: OpenPgpFormState,
    onChange: (OpenPgpFormState) -> Unit,
) {
    SectionCard("Operation") {
        Dropdown(
            label = "Action",
            items = OpenPgpOperation.entries,
            selected = state.operation,
            itemLabel = { it.label },
            onSelect = { onChange(state.copy(operation = it)) },
        )
        Text(
            text = "input=${state.operation.needsInput} " +
                "output=${state.operation.outputMode} " +
                "private_key=${state.operation.usesPrivateKey}" +
                if (state.operation.supported) "" else " — expects GENERIC_ERROR",
            style = MaterialTheme.typography.bodySmall,
        )
        TextInput(
            label = "api_version (accepted 7–12)",
            value = state.apiVersion,
            onValueChange = { onChange(state.copy(apiVersion = it)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            API_VERSION_CHIPS.forEach { version ->
                OutlinedButton(
                    onClick = {
                        onChange(
                            state.copy(
                                apiVersion = version.toString(),
                                omitApiVersion = false,
                            ),
                        )
                    },
                ) { Text("$version") }
            }
        }
        ToggleRow(
            label = "omit api_version entirely",
            checked = state.omitApiVersion,
            onCheckedChange = { onChange(state.copy(omitApiVersion = it)) },
        )
    }
}

@Composable
private fun PayloadSection(
    controller: DriverController,
    state: OpenPgpFormState,
    onChange: (OpenPgpFormState) -> Unit,
) {
    SectionCard("Payload") {
        when (val payload = state.payload) {
            is PayloadSource.Text -> TextInput(
                label = "input stream (UTF-8)",
                value = payload.value,
                onValueChange = { onChange(state.copy(payload = PayloadSource.Text(it))) },
                singleLine = false,
            )

            is PayloadSource.Binary -> Column {
                Text(
                    text = "${payload.label}: byte[${payload.bytes.size}]",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        val text = OpenPgpFormState.DEFAULT_PAYLOAD
                        onChange(state.copy(payload = PayloadSource.Text(text)))
                    },
                ) { Text("Replace with text") }
            }
        }
        controller.scratch.lastOutput?.let { output ->
            OutlinedButton(
                onClick = {
                    onChange(
                        state.copy(
                            payload = PayloadSource.Binary("last output", output),
                        ),
                    )
                },
            ) { Text("Use last output (byte[${output.size}])") }
        }
    }
}

@Composable
private fun RecipientSection(
    state: OpenPgpFormState,
    onChange: (OpenPgpFormState) -> Unit,
) {
    SectionCard("Recipients") {
        TextInput(
            label = "user_ids (one per line)",
            value = state.userIds,
            onValueChange = { onChange(state.copy(userIds = it)) },
            singleLine = false,
        )
        ToggleRow(
            label = "send the first one as user_id instead of user_ids",
            checked = state.sendAsSingleUserId,
            onCheckedChange = { onChange(state.copy(sendAsSingleUserId = it)) },
        )
        TextInput(
            label = "key_ids (hex or decimal, comma separated)",
            value = state.keyIds,
            onValueChange = { onChange(state.copy(keyIds = it)) },
        )
        if (OpenPgpField.SELECTED_KEY_IDS in state.operation.fields) {
            TextInput(
                label = "key_ids_selected (merged into key_ids by the provider)",
                value = state.selectedKeyIds,
                onValueChange = { onChange(state.copy(selectedKeyIds = it)) },
            )
        }
    }
}

@Composable
private fun KeySection(
    controller: DriverController,
    state: OpenPgpFormState,
    onChange: (OpenPgpFormState) -> Unit,
) {
    val fields = state.operation.fields
    if (OpenPgpField.SIGN_KEY_ID !in fields && OpenPgpField.KEY_ID !in fields) return
    SectionCard("Keys") {
        if (OpenPgpField.SIGN_KEY_ID in fields) {
            TextInput(
                label = "sign_key_id",
                value = state.signKeyId,
                onValueChange = { onChange(state.copy(signKeyId = it)) },
            )
            controller.scratch.signKeyId?.let { keyId ->
                OutlinedButton(
                    onClick = { onChange(state.copy(signKeyId = keyId.toKeyIdHex())) },
                ) { Text("Use ${keyId.toKeyIdHex()}") }
            }
            if (OpenPgpField.PRESELECT_KEY_ID in fields) {
                ToggleRow(
                    label = "send it as preselect_key_id instead",
                    checked = state.sendSignKeyIdAsPreselect,
                    onCheckedChange = {
                        onChange(state.copy(sendSignKeyIdAsPreselect = it))
                    },
                )
            }
        }
        if (OpenPgpField.KEY_ID in fields) {
            TextInput(
                label = "key_id (required for GET_KEY)",
                value = state.keyId,
                onValueChange = { onChange(state.copy(keyId = it)) },
            )
        }
    }
}

@Composable
private fun OptionSection(
    state: OpenPgpFormState,
    onChange: (OpenPgpFormState) -> Unit,
) {
    val fields = state.operation.fields
    SectionCard("Options") {
        if (OpenPgpField.ORIGINAL_FILENAME in fields) {
            TextInput(
                label = "original_filename",
                value = state.originalFilename,
                onValueChange = { onChange(state.copy(originalFilename = it)) },
            )
        }
        TriStateRow("ascii_armor", state.asciiArmor) {
            onChange(state.copy(asciiArmor = it))
        }
        TriStateRow("enable_compression (provider default: true)", state.enableCompression) {
            onChange(state.copy(enableCompression = it))
        }
        TriStateRow("opportunistic", state.opportunistic) {
            onChange(state.copy(opportunistic = it))
        }
        if (OpenPgpField.DETACHED_SIGNATURE in fields) {
            DetachedSignatureRow(state, onChange)
        }
    }
}

@Composable
private fun DetachedSignatureRow(
    state: OpenPgpFormState,
    onChange: (OpenPgpFormState) -> Unit,
) {
    Text(
        text = "detached_signature = " +
            (state.detachedSignature?.let { "byte[${it.size}]" } ?: "omitted"),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { onChange(state.copy(detachedSignature = null)) }) {
            Text("Clear")
        }
    }
}

@Composable
private fun ViolationSection(
    state: OpenPgpFormState,
    onChange: (OpenPgpFormState) -> Unit,
) {
    SectionCard("Deliberate violations") {
        ToggleRow("send custom_headers (hard rejection)", state.customHeaders) {
            onChange(state.copy(customHeaders = it))
        }
        ToggleRow("send minimize = true (hard rejection)", state.minimize) {
            onChange(state.copy(minimize = it))
        }
        ToggleRow("omit the input stream", state.omitInput) {
            onChange(state.copy(omitInput = it))
        }
        ToggleRow("omit the output pipe", state.omitOutputPipe) {
            onChange(state.copy(omitOutputPipe = it))
        }
        TextInput(
            label = "send an output pipe id we never created",
            value = state.foreignPipeId,
            onValueChange = { onChange(state.copy(foreignPipeId = it)) },
        )
    }
}

private val RECIPIENT_FIELDS = setOf(
    OpenPgpField.USER_IDS,
    OpenPgpField.SINGLE_USER_ID,
    OpenPgpField.KEY_IDS,
)

private val API_VERSION_CHIPS = listOf(6, 7, 11, 12, 13)
