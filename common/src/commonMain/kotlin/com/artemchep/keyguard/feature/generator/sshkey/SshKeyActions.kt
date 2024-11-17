package com.artemchep.keyguard.feature.generator.sshkey

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.KeyPairExport
import com.artemchep.keyguard.common.usecase.KeyPrivateExport
import com.artemchep.keyguard.common.usecase.KeyPublicExport
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ContextItemBuilder
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.StringResource

object SshKeyActions {
    context(RememberStateFlowScope, ContextItemBuilder)
    fun addAll(
        keyPair: KeyPair,
        keyPairExport: KeyPairExport,
        publicKeyExport: KeyPublicExport,
        privateKeyExport: KeyPrivateExport,
        copyItemFactory: CopyText,
    ) {
        section {
            this += copyItemFactory.FlatItemAction(
                title = Res.string.copy_ssh_public_key.wrap(),
                value = keyPair.publicKey.ssh,
                type = CopyText.Type.KEY,
            )
            this += copyItemFactory.FlatItemAction(
                title = Res.string.copy_ssh_fingerprint.wrap(),
                value = keyPair.publicKey.fingerprint,
                type = CopyText.Type.FINGERPRINT,
            )
            this += copyItemFactory.FlatItemAction(
                title = Res.string.copy_ssh_unencrypted_private_key.wrap(),
                value = keyPair.privateKey.ssh,
                type = CopyText.Type.KEY,
                hidden = true,
            )
        }
        section {
            this += savePublicKey(
                keyPair = keyPair,
                publicKeyExport = publicKeyExport,
            )
            this += savePrivateKey(
                keyPair = keyPair,
                privateKeyExport = privateKeyExport,
            )
            this += FlatItemAction(
                leading = icon(Icons.Outlined.Save),
                title = Res.string.ssh_key_action_save_unencrypted_keys_title.wrap(),
                onClick = {
                    keyPairExport(keyPair)
                        .effectTap { uri ->
                            val title =
                                Res.string.ssh_key_action_save_unencrypted_keys_saved_downloads_success_title
                            sendSuccessMessage(
                                title = title,
                                uri = uri,
                            )
                        }
                        .launchIn(appScope)
                },
            )
        }
    }

    context(RememberStateFlowScope)
    fun savePublicKey(
        keyPair: KeyPair,
        publicKeyExport: KeyPublicExport,
    ) : FlatItemAction {
        return FlatItemAction(
            leading = icon(Icons.Outlined.Save),
            title = Res.string.ssh_key_action_save_public_key_title.wrap(),
            onClick = {
                publicKeyExport(keyPair.publicKey)
                    .effectTap { uri ->
                        val title =
                            Res.string.ssh_key_action_save_public_key_saved_downloads_success_title
                        sendSuccessMessage(
                            title = title,
                            uri = uri,
                        )
                    }
                    .launchIn(appScope)
            },
        )
    }

    context(RememberStateFlowScope)
    fun savePrivateKey(
        keyPair: KeyPair,
        privateKeyExport: KeyPrivateExport,
    ) : FlatItemAction {
        return FlatItemAction(
            leading = icon(Icons.Outlined.Save),
            title = Res.string.ssh_key_action_save_unencrypted_private_key_title.wrap(),
            onClick = {
                privateKeyExport(keyPair.privateKey)
                    .effectTap { uri ->
                        val title =
                            Res.string.ssh_key_action_save_unencrypted_private_key_saved_downloads_success_title
                        sendSuccessMessage(
                            title = title,
                            uri = uri,
                        )
                    }
                    .launchIn(appScope)
            },
        )
    }

    context(RememberStateFlowScope)
    private suspend fun sendSuccessMessage(
        title: StringResource,
        uri: String? = null,
    ) {
        val action = uri?.let { u ->
            ToastMessage.Action(
                title = translate(Res.string.file_action_reveal_title),
                onClick = {
                    val intent = NavigationIntent.NavigateToPreviewInFileManager(
                        uri = u,
                    )
                    navigate(intent)
                },
            )
        }

        val msg = ToastMessage(
            title = translate(title),
            type = ToastMessage.Type.SUCCESS,
            action = action,
        )
        message(msg)
    }
}
