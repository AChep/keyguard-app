package com.artemchep.keyguard.feature.generator.gpgkey

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GpgKeyExport
import com.artemchep.keyguard.common.usecase.GpgKeyPrivateExport
import com.artemchep.keyguard.common.usecase.GpgKeyPublicExport
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ContextItemBuilder
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.StringResource

object GpgKeyActions {
    context(stateScope: RememberStateFlowScope, contextItemBuilder: ContextItemBuilder)
    fun addAll(
        gpgKey: GeneratedGpgKey,
        gpgKeyExport: GpgKeyExport,
        publicKeyExport: GpgKeyPublicExport,
        privateKeyExport: GpgKeyPrivateExport,
        copyItemFactory: CopyText,
    ) = with(stateScope) {
        with(contextItemBuilder) {
            section {
                this += copyItemFactory.FlatItemAction(
                    title = Res.string.copy_gpg_public_key.wrap(),
                    value = gpgKey.publicKeyArmored,
                    type = CopyText.Type.PUBLIC_KEY,
                )
                this += copyItemFactory.FlatItemAction(
                    title = Res.string.copy_gpg_fingerprint.wrap(),
                    value = gpgKey.fingerprint,
                    type = CopyText.Type.FINGERPRINT,
                )
                this += copyItemFactory.FlatItemAction(
                    title = Res.string.copy_gpg_unencrypted_private_key.wrap(),
                    value = gpgKey.privateKeyArmored,
                    type = CopyText.Type.PRIVATE_KEY,
                    hidden = true,
                )
            }
            section {
                this += savePublicKey(
                    gpgKey = gpgKey,
                    publicKeyExport = publicKeyExport,
                )
                this += savePrivateKey(
                    gpgKey = gpgKey,
                    privateKeyExport = privateKeyExport,
                )
                this += FlatItemAction(
                    leading = icon(Icons.Outlined.Save),
                    title = Res.string.gpg_key_action_save_unencrypted_keys_title.wrap(),
                    onClick = {
                        gpgKeyExport(gpgKey)
                            .effectTap { uri ->
                                sendSuccessMessage(
                                    title = Res.string.gpg_key_action_save_unencrypted_keys_saved_downloads_success_title,
                                    uri = uri,
                                )
                            }
                            .launchIn(appScope)
                    },
                )
            }
        }
    }

    context(stateScope: RememberStateFlowScope)
    fun savePublicKey(
        gpgKey: GeneratedGpgKey,
        publicKeyExport: GpgKeyPublicExport,
    ): FlatItemAction = with(stateScope) {
        FlatItemAction(
            leading = icon(Icons.Outlined.Save),
            title = Res.string.gpg_key_action_save_public_key_title.wrap(),
            onClick = {
                publicKeyExport(gpgKey)
                    .effectTap { uri ->
                        sendSuccessMessage(
                            title = Res.string.gpg_key_action_save_public_key_saved_downloads_success_title,
                            uri = uri,
                        )
                    }
                    .launchIn(appScope)
            },
        )
    }

    context(stateScope: RememberStateFlowScope)
    fun savePrivateKey(
        gpgKey: GeneratedGpgKey,
        privateKeyExport: GpgKeyPrivateExport,
    ): FlatItemAction = with(stateScope) {
        FlatItemAction(
            leading = icon(Icons.Outlined.Save),
            title = Res.string.gpg_key_action_save_unencrypted_private_key_title.wrap(),
            onClick = {
                privateKeyExport(gpgKey)
                    .effectTap { uri ->
                        sendSuccessMessage(
                            title = Res.string.gpg_key_action_save_unencrypted_private_key_saved_downloads_success_title,
                            uri = uri,
                        )
                    }
                    .launchIn(appScope)
            },
        )
    }

    context(stateScope: RememberStateFlowScope)
    private suspend fun sendSuccessMessage(
        title: StringResource,
        uri: String? = null,
    ) = with(stateScope) {
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
        message(
            ToastMessage(
                title = translate(title),
                type = ToastMessage.Type.SUCCESS,
                action = action,
            ),
        )
    }
}
