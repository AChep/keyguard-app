package com.artemchep.keyguard.feature.gpgagent.tools

import androidx.compose.runtime.Composable
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

object GpgToolsEncryptRoute : Route {
    @Composable
    override fun Content() {
        GpgToolsOperationScreen(
            operation = GpgToolsOperation.ENCRYPT,
        )
    }
}

object GpgToolsDecryptRoute : Route {
    @Composable
    override fun Content() {
        GpgToolsOperationScreen(
            operation = GpgToolsOperation.DECRYPT,
        )
    }
}

object GpgToolsSignRoute : Route {
    @Composable
    override fun Content() {
        GpgToolsOperationScreen(
            operation = GpgToolsOperation.SIGN,
        )
    }
}

object GpgToolsVerifyRoute : Route {
    @Composable
    override fun Content() {
        GpgToolsOperationScreen(
            operation = GpgToolsOperation.VERIFY,
        )
    }
}
