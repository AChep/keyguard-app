package com.artemchep.keyguard.feature.home.vault.add

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.feature.environments.edit.EnvironmentEditRoute
import com.artemchep.keyguard.feature.navigation.Route

/**
 * Whether the generic editor can save the items of this type. Merging
 * goes through the generic editor, so it also decides that.
 */
fun DSecret.Type.hasGenericEditor(): Boolean = this != DSecret.Type.Environment

fun LeAddRoute(
    args: AddRoute.Args = AddRoute.Args(),
): Route {
    // Environments have a dedicated editor: the generic
    // one would persist their secrets as a draft.
    val type = args.initialValue?.type ?: args.type
    if (
        type == DSecret.Type.Environment &&
        args.merge == null
    ) {
        return EnvironmentEditRoute(
            environmentId = args.initialValue?.id,
        )
    }
    return AddRouteImpl(
        args = args,
    )
}

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
        val note: String? = null,
        val keyPair: KeyPair? = null,
        val gpgKey: GeneratedGpgKey? = null,
        val gpgKeyValue: DSecret.GpgKey? = null,
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
