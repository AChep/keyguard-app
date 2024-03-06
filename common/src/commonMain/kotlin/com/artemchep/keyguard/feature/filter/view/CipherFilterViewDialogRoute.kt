package com.artemchep.keyguard.feature.filter.view

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.feature.navigation.DialogRoute

data class CipherFilterViewDialogRoute(
    val args: Args,
) : DialogRoute {
    data class Args(
        val model: DCipherFilter,
    )

    @Composable
    override fun Content() {
    }
}
