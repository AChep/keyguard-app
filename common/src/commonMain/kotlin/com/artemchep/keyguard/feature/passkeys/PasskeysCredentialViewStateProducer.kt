package com.artemchep.keyguard.feature.passkeys

import androidx.compose.runtime.Composable
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun producePasskeysCredentialViewState(
    args: PasskeysCredentialViewRoute.Args,
    mode: AppMode,
) = with(localDI().direct) {
    producePasskeysCredentialViewState(
        args = args,
        mode = mode,
        getCiphers = instance(),
        passkeyTargetCheck = instance(),
        dateFormatter = instance(),
    )
}

@Composable
fun producePasskeysCredentialViewState(
    args: PasskeysCredentialViewRoute.Args,
    mode: AppMode,
    getCiphers: GetCiphers,
    passkeyTargetCheck: PasskeyTargetCheck,
    dateFormatter: DateFormatter,
): Loadable<PasskeysCredentialViewState> = produceScreenState(
    key = "passkeys_credential_view",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val contentFlow = getCiphers()
        .map { ciphers ->
            val cipherOrNull = ciphers.firstOrNull { it.id == args.cipherId }
            val credentialOrNull = cipherOrNull
                ?.login
                ?.fido2Credentials
                ?.firstOrNull { it.credentialId == args.credentialId }
            credentialOrNull
        }
        .onStart {
            emit(args.model)
        }
        .map { credential ->
            if (credential == null) {
                val e = RuntimeException("Credential does not exist.")
                return@map e.left()
            }

            // If we are in the pick passkey mode then allow a use to use
            // the passkey.
            val onUse = if (mode is AppMode.PickPasskey) {
                val matches = passkeyTargetCheck(credential, mode.target)
                    .attempt()
                    .bind()
                    .isRight { it }
                if (matches) {
                    // lambda
                    {
                        mode.onComplete(credential)
                    }
                } else {
                    null
                }
            } else {
                null
            }

            val createdAt = translate(
                Res.strings.vault_view_passkey_created_at_label,
                dateFormatter.formatDateTime(credential.creationDate),
            )
            val content = PasskeysCredentialViewState.Content(
                model = credential,
                createdAt = createdAt,
                onUse = onUse,
            )
            content.right()
        }
    contentFlow
        .map { contentResult ->
            val state = PasskeysCredentialViewState(
                content = contentResult,
                onClose = {
                    navigatePopSelf()
                },
            )
            Loadable.Ok(state)
        }
}
