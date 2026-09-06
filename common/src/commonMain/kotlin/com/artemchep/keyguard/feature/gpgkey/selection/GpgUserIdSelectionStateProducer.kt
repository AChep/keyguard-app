package com.artemchep.keyguard.feature.gpgkey.selection

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_user_id_revocation_last_identity_message
import com.artemchep.keyguard.res.gpg_user_id_revocation_no_identity_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_no_identity_message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource

@Composable
fun gpgUserIdSelectionState(
    args: GpgUserIdSelectionRoute.Args,
    transmitter: RouteResultTransmitter<String>,
): GpgUserIdSelectionState = produceScreenState(
    key = "gpg_user_id_selection",
    initial = GpgUserIdSelectionState(),
    args = arrayOf(args),
) {
    gpgUserIdSelectionStateProducer(
        args = args,
        transmitter = transmitter,
    )
}

internal suspend fun RememberStateFlowScope.gpgUserIdSelectionStateProducer(
    args: GpgUserIdSelectionRoute.Args,
    transmitter: RouteResultTransmitter<String>,
): Flow<GpgUserIdSelectionState> {
    val identities = args.activeIdentities
    val selectedIdentitySink = mutablePersistedFlow("identity") {
        identities.firstOrNull()?.identityId.orEmpty()
    }
    val error = evaluateGpgUserIdSelection(
        mode = args.mode,
        identityCount = identities.size,
    )
    val errorText = error
        ?.let { value ->
            gpgUserIdSelectionErrorResource(
                error = value,
                mode = args.mode,
            )
        }
        ?.let { resource -> translate(resource) }

    return selectedIdentitySink.map { selectedIdentityId ->
        val canConfirm = confirmedGpgUserIdSelection(
            mode = args.mode,
            identities = identities,
            selectedIdentityId = selectedIdentityId,
        ) != null
        GpgUserIdSelectionState(
            identities = identities.map { identity ->
                GpgUserIdSelectionState.Identity(
                    key = identity.identityId,
                    title = identity.userId,
                    selected = identity.identityId == selectedIdentityId,
                    onSelect = {
                        selectedIdentitySink.value = identity.identityId
                    },
                )
            },
            validationError = errorText,
            onDeny = ::navigatePopSelf,
            onConfirm = if (canConfirm) {
                {
                    confirmedGpgUserIdSelection(
                        mode = args.mode,
                        identities = identities,
                        selectedIdentityId = selectedIdentitySink.value,
                    )?.let { identityId ->
                        transmitter(identityId)
                        navigatePopSelf()
                    }
                }
            } else {
                null
            },
        )
    }
}

internal enum class GpgUserIdSelectionError {
    NoIdentity,
    LastIdentity,
}

internal fun evaluateGpgUserIdSelection(
    mode: GpgUserIdSelectionRoute.Args.Mode,
    identityCount: Int,
): GpgUserIdSelectionError? = when {
    identityCount == 0 -> GpgUserIdSelectionError.NoIdentity
    identityCount == 1 && mode == GpgUserIdSelectionRoute.Args.Mode.Revocation ->
        GpgUserIdSelectionError.LastIdentity

    else -> null
}

internal fun confirmedGpgUserIdSelection(
    mode: GpgUserIdSelectionRoute.Args.Mode,
    identities: List<GpgUserIdSelectionIdentity>,
    selectedIdentityId: String,
): String? = selectedIdentityId.takeIf { identityId ->
    evaluateGpgUserIdSelection(
        mode = mode,
        identityCount = identities.size,
    ) == null && identities.any { identity ->
        identity.identityId == identityId
    }
}

internal fun gpgUserIdSelectionErrorResource(
    error: GpgUserIdSelectionError,
    mode: GpgUserIdSelectionRoute.Args.Mode,
): StringResource = when (error) {
    GpgUserIdSelectionError.NoIdentity -> when (mode) {
        GpgUserIdSelectionRoute.Args.Mode.Replacement ->
            Res.string.gpg_user_id_replacement_no_identity_message

        GpgUserIdSelectionRoute.Args.Mode.Revocation ->
            Res.string.gpg_user_id_revocation_no_identity_message
    }

    GpgUserIdSelectionError.LastIdentity ->
        Res.string.gpg_user_id_revocation_last_identity_message
}
