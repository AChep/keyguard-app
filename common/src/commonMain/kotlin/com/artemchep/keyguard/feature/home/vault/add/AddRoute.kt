package com.artemchep.keyguard.feature.home.vault.add

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.navigation.Route

fun LeAddRoute(
    args: AddRoute.Args = AddRoute.Args(),
): Route =
    AddRouteImpl(
        args = args,
    )

data class AddRouteImpl(
    val args: AddRoute.Args = AddRoute.Args(),
) : Route {
    @Composable
    override fun Content() {
        AddScreen(
            args = args,
        )
    }
}

interface AddRoute {
    data class Args(
        val behavior: Behavior = Behavior(),
        val merge: Merge? = null,
        val initialValue: DSecret? = null,
        val ownershipRo: Boolean = initialValue != null,
        val type: DSecret.Type? = null,
        val name: String? = null,
        val username: String? = null,
        val password: String? = null,
        val autofill: Autofill? = null,
    ) {
        data class Behavior(
            val autoShowKeyboard: Boolean = true,
            val launchEditedCipher: Boolean = true,
        )

        data class Merge(
            val ciphers: List<DSecret>,
        )

        data class Autofill(
            val applicationId: String? = null,
            val webDomain: String? = null,
            val webScheme: String? = null,
            // login
            val username: String? = null,
            val password: String? = null,
            // identity
            val email: String? = null,
            val phone: String? = null,
            val personName: String? = null,
        ) {
            companion object
        }
    }
}
