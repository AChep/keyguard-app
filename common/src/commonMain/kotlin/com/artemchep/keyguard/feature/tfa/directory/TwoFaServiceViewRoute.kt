package com.artemchep.keyguard.feature.tfa.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.feature.navigation.DialogRoute

data class TwoFaServiceViewRoute(
    val args: Args,
) : DialogRoute {
    data class Args(
        val model: TwoFaServiceInfo,
    )

    @Composable
    override fun Content() {
        TwoFaServiceViewScreen(
            args = args,
        )
    }
}
