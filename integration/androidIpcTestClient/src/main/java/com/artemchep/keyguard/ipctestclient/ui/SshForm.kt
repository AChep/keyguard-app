package com.artemchep.keyguard.ipctestclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ipctestclient.ipc.SshOperation
import com.artemchep.keyguard.ipctestclient.ipc.sshHashAlgorithmName

@Composable
fun SshForm(controller: DriverController) {
    val state = controller.sshForm
    val onChange: (SshFormState) -> Unit = { controller.sshForm = it }
    OperationSection(state, onChange)
    KeySection(controller, state, onChange)
    if (state.operation.needsChallenge) {
        ChallengeSection(state, onChange)
    }
    Button(
        onClick = controller::runSsh,
        enabled = !controller.busy,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(if (controller.busy) "Running…" else "Send ${state.operation.label}")
    }
}

@Composable
private fun OperationSection(
    state: SshFormState,
    onChange: (SshFormState) -> Unit,
) {
    SectionCard("Operation") {
        Dropdown(
            label = "Action",
            items = SshOperation.entries,
            selected = state.operation,
            itemLabel = { it.label },
            onSelect = { onChange(state.copy(operation = it)) },
        )
        TextInput(
            label = "api_version (only 1 is accepted)",
            value = state.apiVersion,
            onValueChange = { onChange(state.copy(apiVersion = it)) },
        )
        ToggleRow(
            label = "omit api_version entirely",
            checked = state.omitApiVersion,
            onCheckedChange = { onChange(state.copy(omitApiVersion = it)) },
        )
        ToggleRow(
            label = "build the intent with the official request classes",
            checked = state.useLibraryBuilders,
            onCheckedChange = { onChange(state.copy(useLibraryBuilders = it)) },
        )
    }
}

@Composable
private fun KeySection(
    controller: DriverController,
    state: SshFormState,
    onChange: (SshFormState) -> Unit,
) {
    SectionCard("Key") {
        TextInput(
            label = "key_id",
            value = state.keyId,
            onValueChange = { onChange(state.copy(keyId = it)) },
        )
        controller.scratch.sshKeyId?.let { keyId ->
            OutlinedButton(onClick = { onChange(state.copy(keyId = keyId)) }) {
                Text("Use last selected key")
            }
        }
        controller.scratch.sshPublicKey?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChallengeSection(
    state: SshFormState,
    onChange: (SshFormState) -> Unit,
) {
    SectionCard("Challenge") {
        TextInput(
            label = "challenge (UTF-8)",
            value = state.challenge,
            onValueChange = { onChange(state.copy(challenge = it)) },
            singleLine = false,
        )
        Text(
            text = "Ed25519 keys sign the challenge as-is; the hash algorithm only " +
                "picks the RSA signature variant.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Dropdown(
                label = "hash_algorithm",
                items = SshOperation.API_HASH_ALGORITHMS,
                selected = state.hashAlgorithm,
                itemLabel = { algorithm ->
                    val supportedByRsa = algorithm in SshOperation.RSA_HASH_ALGORITHMS
                    sshHashAlgorithmName(algorithm) +
                        if (supportedByRsa) "" else " (Ed25519 only)"
                },
                onSelect = { onChange(state.copy(hashAlgorithm = it)) },
            )
        }
    }
}
