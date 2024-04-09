package com.artemchep.keyguard.feature.send.add

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.navigation.Route

class SendAddRoute(
    val args: Args,
) : Route {
    data class Args(
        val type: DSend.Type,
        val behavior: Behavior = Behavior(),
        val name: String? = null,
        val text: String? = null,
        val initialValue: DSend? = null,
        val ownershipRo: Boolean = initialValue != null,
    ) {
        data class Behavior(
            val autoShowKeyboard: Boolean = true,
            val launchEditedCipher: Boolean = true,
        )
    }

    @Composable
    override fun Content() {
        SendAddScreen(
            args = args,
        )
    }
}
