package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.feature.navigation.DialogRoute

data class PasskeysServiceViewDialogRoute(
    val args: Args,
) : DialogRoute {
    data class Args(
        val model: PassKeyServiceInfo,
    )

    @Composable
    override fun Content() {
        PasskeysViewDialogScreen(
            args = args,
        )
    }
}
