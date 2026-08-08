package com.artemchep.keyguard.ipctestclient.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class Protocol(val label: String) {
    OPENPGP("OpenPGP"),
    SSH("SSH"),
}

@Composable
fun DriverScreen(controller: DriverController) {
    var protocol by remember { mutableStateOf(Protocol.OPENPGP) }
    LaunchedEffect(Unit) { controller.refresh() }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ConnectionCard(controller)
            PrimaryTabRow(selectedTabIndex = protocol.ordinal) {
                Protocol.entries.forEach { entry ->
                    Tab(
                        selected = protocol == entry,
                        onClick = { protocol = entry },
                        text = { Text(entry.label) },
                    )
                }
            }
            when (protocol) {
                Protocol.OPENPGP -> OpenPgpForm(controller)
                Protocol.SSH -> SshForm(controller)
            }
            ScratchpadCard(controller)
            ResultPanel(controller)
        }
    }
}
