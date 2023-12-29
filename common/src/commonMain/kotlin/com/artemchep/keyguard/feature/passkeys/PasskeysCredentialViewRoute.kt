package com.artemchep.keyguard.feature.passkeys

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.navigation.DialogRoute

data class PasskeysCredentialViewRoute(
    val args: Args,
) : DialogRoute {
    data class Args(
        val cipherId: String,
        val credentialId: String,
        val model: DSecret.Login.Fido2Credentials,
    )

    @Composable
    override fun Content() {
        PasskeysCredentialViewScreen(
            args = args,
        )
    }
}
