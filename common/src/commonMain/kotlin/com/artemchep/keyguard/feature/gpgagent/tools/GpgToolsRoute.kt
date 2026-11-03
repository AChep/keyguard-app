package com.artemchep.keyguard.feature.gpgagent.tools

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

object GpgToolsRoute : Route {
    const val ROUTER_NAME = "gpg_tools"

    override val descriptor get() = RouteDescriptor.GpgTools

    @Composable
    override fun Content() {
        GpgToolsScreen()
    }
}

class GpgToolsOperationRoute(
    val args: Args,
) : Route {
    data class Args(
        val operation: GpgToolsOperation,
        val inputFile: FilePickerResult? = null,
    )

    @Composable
    override fun Content() {
        GpgToolsOperationScreen(
            operation = args.operation,
            inputFile = args.inputFile,
        )
    }
}
