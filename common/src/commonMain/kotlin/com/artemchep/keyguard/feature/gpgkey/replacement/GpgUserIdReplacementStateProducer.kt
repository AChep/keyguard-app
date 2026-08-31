package com.artemchep.keyguard.feature.gpgkey.replacement

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.nativecrypto.isValidOpenPgpUserId
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_user_id_replacement_duplicate_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_invalid_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_same_identity_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_value_hint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource

@Composable
fun gpgUserIdReplacementState(
    args: GpgUserIdReplacementRoute.Args,
    transmitter: RouteResultTransmitter<String>,
): GpgUserIdReplacementState = produceScreenState(
    key = "gpg_user_id_replacement",
    initial = GpgUserIdReplacementState(),
    args = arrayOf(args),
) {
    gpgUserIdReplacementStateProducer(
        args = args,
        transmitter = transmitter,
    )
}

internal suspend fun RememberStateFlowScope.gpgUserIdReplacementStateProducer(
    args: GpgUserIdReplacementRoute.Args,
    transmitter: RouteResultTransmitter<String>,
): Flow<GpgUserIdReplacementState> {
    val valueHandle = textFieldHandle(
        key = "value",
        initial = args.initialValue,
    )
    val hint = translate(Res.string.gpg_user_id_replacement_value_hint)
    val errorTexts = GpgUserIdReplacementError.entries.associateWith { error ->
        translate(gpgUserIdReplacementErrorResource(error))
    }

    return valueHandle.sink.map { cell ->
        val error = validateGpgUserIdReplacement(
            oldUserId = args.oldUserId,
            activeUserIds = args.activeUserIds,
            newUserId = cell.text,
        )
        val validated = if (error == null) {
            Validated.Success(cell.text)
        } else {
            Validated.Failure(
                model = cell.text,
                error = errorTexts.getValue(error),
            )
        }
        GpgUserIdReplacementState(
            value = TextFieldModel.of(
                cell = cell,
                handle = valueHandle,
                validated = validated,
                hint = hint,
            ),
            onDeny = ::navigatePopSelf,
            onConfirm = if (error == null) {
                {
                    confirmedGpgUserIdReplacement(
                        oldUserId = args.oldUserId,
                        activeUserIds = args.activeUserIds,
                        newUserId = valueHandle.sink.value.text,
                    )?.let { newUserId ->
                        transmitter(newUserId)
                        navigatePopSelf()
                    }
                }
            } else {
                null
            },
        )
    }
}

internal enum class GpgUserIdReplacementError {
    InvalidFormat,
    SameIdentity,
    DuplicateIdentity,
}

internal fun validateGpgUserIdReplacement(
    oldUserId: String,
    activeUserIds: List<String>,
    newUserId: String,
): GpgUserIdReplacementError? = when {
    !newUserId.isValidOpenPgpUserId() ->
        GpgUserIdReplacementError.InvalidFormat

    newUserId == oldUserId ->
        GpgUserIdReplacementError.SameIdentity

    newUserId in activeUserIds ->
        GpgUserIdReplacementError.DuplicateIdentity

    else -> null
}

internal fun confirmedGpgUserIdReplacement(
    oldUserId: String,
    activeUserIds: List<String>,
    newUserId: String,
): String? = newUserId.takeIf {
    validateGpgUserIdReplacement(
        oldUserId = oldUserId,
        activeUserIds = activeUserIds,
        newUserId = newUserId,
    ) == null
}

internal fun gpgUserIdReplacementErrorResource(
    error: GpgUserIdReplacementError,
): StringResource = when (error) {
    GpgUserIdReplacementError.InvalidFormat ->
        Res.string.gpg_user_id_replacement_invalid_message

    GpgUserIdReplacementError.SameIdentity ->
        Res.string.gpg_user_id_replacement_same_identity_message

    GpgUserIdReplacementError.DuplicateIdentity ->
        Res.string.gpg_user_id_replacement_duplicate_message
}
