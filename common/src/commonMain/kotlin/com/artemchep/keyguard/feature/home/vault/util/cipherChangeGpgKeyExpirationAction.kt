package com.artemchep.keyguard.feature.home.vault.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.biFlatTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.parsePrimaryKeyInfo
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentFingerprint
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentPublicKeyArmored
import com.artemchep.keyguard.common.usecase.ChangeGpgKeyExpirationById
import com.artemchep.keyguard.common.usecase.ChangeGpgKeyExpirationByIdRequest
import com.artemchep.keyguard.common.usecase.ChangeGpgKeyExpirationByIdResult
import com.artemchep.keyguard.feature.gpgkey.expiration.createLocalizedGpgKeyExpirationFailureToast
import com.artemchep.keyguard.feature.gpgkey.expiration.requestGpgKeyExpirationChange
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun RememberStateFlowScope.cipherChangeGpgKeyExpiryAction(
    gpgPublicKeyParser: GpgPublicKeyParser,
    changeGpgKeyExpirationById: ChangeGpgKeyExpirationById,
    cipher: DSecret,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = FlatItemAction(
    id = "cipher.changeGpgKeyExpiry",
    leading = icon(Icons.Outlined.CalendarMonth),
    title = Res.string.ciphers_action_change_gpg_key_expiry_title.wrap(),
    onClick = {
        before?.invoke()
        action {
            val publicKeyArmored = cipher.getGpgAgentPublicKeyArmored()
                ?.takeIf { it.isNotBlank() }
            if (publicKeyArmored == null) {
                message(createLocalizedGpgKeyExpirationFailureToast())
                after?.invoke(false)
                return@action
            }
            val keyFingerprint = cipher.getGpgAgentFingerprint()
            val keyInfo = withContext(Dispatchers.Default) {
                gpgPublicKeyParser.parsePrimaryKeyInfo(
                    armored = publicKeyArmored,
                    fingerprint = keyFingerprint,
                )
            }
            if (keyInfo == null) {
                message(createLocalizedGpgKeyExpirationFailureToast())
                after?.invoke(false)
                return@action
            }
            requestGpgKeyExpirationChange(
                keyInfo = keyInfo,
            ) { change ->
                changeGpgKeyExpirationById(
                    ChangeGpgKeyExpirationByIdRequest(
                        cipherId = cipher.id,
                        expectedPublicKeyArmored = publicKeyArmored,
                        expectedKeyFingerprint = keyFingerprint,
                        change = change,
                    ),
                )
                    .biFlatTap(
                        ifException = { e ->
                            ioEffect {
                                message(e)
                                after?.invoke(false)
                            }
                        },
                        ifSuccess = { result ->
                            ioEffect {
                                when (result) {
                                    ChangeGpgKeyExpirationByIdResult.Success -> {
                                        message(
                                            ToastMessage(
                                                type = ToastMessage.Type.SUCCESS,
                                                title = translate(Res.string.gpg_key_expiry_success_title),
                                                text = translate(Res.string.gpg_key_expiry_cipher_success_message),
                                            ),
                                        )
                                        after?.invoke(true)
                                    }

                                    is ChangeGpgKeyExpirationByIdResult.NotChanged -> {
                                        message(createLocalizedGpgKeyExpirationFailureToast())
                                        after?.invoke(false)
                                    }

                                    is ChangeGpgKeyExpirationByIdResult.CryptoFailure -> {
                                        message(
                                            createLocalizedGpgKeyExpirationFailureToast(result.reason),
                                        )
                                        after?.invoke(false)
                                    }
                                }
                            }
                        },
                    )
                    .attempt()
                    .launchIn(appScope)
            }
        }
    },
)
