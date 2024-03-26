package com.artemchep.keyguard.feature.send.add

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.navigation.Route

class SendAddRoute(
    val args: Args,
) : Route {
    data class Args(
        val type: DSend.Type,
        val name: String? = null,
        val text: String? = null,
        val initialValue: DSend? = null,
        val ownershipRo: Boolean = initialValue != null,
    )

    @Composable
    override fun Content() {
        SendAddScreen(
            args = args,
        )
    }
}
