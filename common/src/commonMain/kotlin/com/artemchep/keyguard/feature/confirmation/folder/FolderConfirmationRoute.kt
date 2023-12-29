package com.artemchep.keyguard.feature.confirmation.folder

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

class FolderConfirmationRoute(
    val args: Args,
) : DialogRouteForResult<FolderConfirmationResult> {
    data class Args(
        val accountId: AccountId,
        val blacklistedFolderIds: Set<String?> = emptySet(),
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<FolderConfirmationResult>,
    ) {
        FolderConfirmationScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}
