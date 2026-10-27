package com.artemchep.keyguard.feature.home.vault.link.field

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

/**
 * Picks a field of an item of the account: first the item, then
 * its password or one of its custom fields. Values are never shown.
 */
data class CipherFieldPickerRoute(
    val args: Args,
) : DialogRouteForResult<CipherFieldPickerResult> {
    data class Args(
        val accountId: String,
        /** An item that can not be picked, for example the one being edited. */
        val excludedCipherId: String? = null,
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<CipherFieldPickerResult>,
    ) {
        CipherFieldPickerScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}

sealed interface CipherFieldPickerResult {
    data class Confirm(
        /** The canonical provider id of the picked item. */
        val cipherId: String,
        val field: EnvironmentFieldReference,
        /** The name of the picked item, to suggest names with. */
        val cipherName: String,
    ) : CipherFieldPickerResult

    data object Deny : CipherFieldPickerResult
}
