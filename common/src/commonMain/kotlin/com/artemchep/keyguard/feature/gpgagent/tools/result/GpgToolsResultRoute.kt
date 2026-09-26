package com.artemchep.keyguard.feature.gpgagent.tools.result

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.ui.SimpleNote

class GpgToolsResultRoute(
    val args: Args,
) : DialogRoute {
    data class Args(
        val title: String,
        val notes: List<SimpleNote> = emptyList(),
        val output: Output? = null,
        val fileOutput: FileOutput? = null,
    ) {
        data class FileOutput(
            val id: String,
            val name: String,
            val size: Long?,
            val incognito: Boolean,
        )

        data class Output(
            val label: String,
            val text: String,
            val incognito: Boolean,
            val onCopy: (() -> Unit)? = null,
            val onSave: (() -> Unit)? = null,
        )
    }

    @Composable
    override fun Content() {
        GpgToolsResultScreen(
            args = args,
        )
    }
}
