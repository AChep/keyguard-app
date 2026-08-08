package com.artemchep.keyguard.ipctestclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ipctestclient.ipc.IpcProviders

@Composable
fun ConnectionCard(controller: DriverController) {
    val state = controller.connections
    SectionCard("Providers") {
        ProviderRow(
            protocol = "OpenPGP v2",
            provider = state.openPgp,
            bound = state.openPgpBound,
        )
        ProviderRow(
            protocol = "SSH Authentication v1",
            provider = state.ssh,
            bound = state.sshBound,
        )
        if (state.legacyOpenPgpProviders.isNotEmpty()) {
            Text(
                text = "Legacy IOpenPgpService is answered by " +
                    state.legacyOpenPgpProviders.joinToString {
                        it.component.packageName
                    } +
                    ". Keyguard must never publish the v1 interface.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = controller::refresh) { Text("Refresh") }
            OutlinedButton(onClick = controller::unbind) { Text("Unbind") }
        }
    }
}

@Composable
private fun ProviderRow(
    protocol: String,
    provider: IpcProviders.Provider?,
    bound: Boolean,
) {
    val detail = when {
        provider == null -> "not available (provider disabled in Keyguard?)"
        bound -> "${provider.component.flattenToShortString()} — bound"
        else -> "${provider.component.flattenToShortString()} — not bound"
    }
    Text(
        text = "$protocol: $detail",
        style = MaterialTheme.typography.bodySmall,
        color = if (provider == null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.padding(top = 4.dp),
    )
}
