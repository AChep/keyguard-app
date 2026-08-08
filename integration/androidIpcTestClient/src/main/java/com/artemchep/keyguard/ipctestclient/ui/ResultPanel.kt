package com.artemchep.keyguard.ipctestclient.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ipctestclient.ipc.preview

@Composable
fun ScratchpadCard(controller: DriverController) {
    val entries = controller.scratch.summary()
    if (entries.isEmpty()) return
    SectionCard("Carried forward") {
        entries.forEach { (label, value) ->
            Text("$label = $value", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = controller::clearScratchpad,
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Clear") }
    }
}

@Composable
fun ResultPanel(controller: DriverController) {
    val report = controller.report ?: return
    var showRaw by remember { mutableStateOf(false) }
    var showTrace by remember { mutableStateOf(false) }
    SectionCard(report.title) {
        Mono(report.decoded)
        report.output?.let {
            Text(
                text = "output stream (byte[${it.size}])",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Mono(it.preview())
        }
        OutlinedButton(
            onClick = { showRaw = !showRaw },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (showRaw) "Hide raw extras" else "Show raw extras") }
        if (showRaw) {
            Mono(report.raw)
        }
        OutlinedButton(onClick = { showTrace = !showTrace }) {
            Text(if (showTrace) "Hide exchange" else "Show exchange")
        }
        if (showTrace) {
            Mono(report.trace)
        }
    }
}
