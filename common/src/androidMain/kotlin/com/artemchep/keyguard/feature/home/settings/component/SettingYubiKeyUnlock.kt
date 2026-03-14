package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.exception.YubiKeyAuthCanceledException
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.vault.FingerprintReadRepository
import com.artemchep.keyguard.common.usecase.DisableYubiKeyUnlock
import com.artemchep.keyguard.common.usecase.EnableYubiKeyUnlock
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.yubikey.YubiKeyProvisionEffect
import com.artemchep.keyguard.feature.yubikey.YubiKeyProvisionProbeEffect
import com.artemchep.keyguard.feature.yubikey.YubiKeyProvisionProbePrompt
import com.artemchep.keyguard.feature.yubikey.YubiKeyProvisionPrompt
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.KeyguardYubiKey
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

private const val YUBIKEY_CHALLENGE_LENGTH = 32
private const val YUBIKEY_SECRET_LENGTH = 20

private const val YUBIKEY_SLOTS_COUNT = 2

actual fun settingYubiKeyUnlockProvider(
    directDI: DirectDI,
): SettingComponent = settingYubiKeyUnlockProvider(
    fingerprintReadRepository = directDI.instance(),
    getVaultSession = directDI.instance(),
    enableYubiKeyUnlock = directDI.instance(),
    disableYubiKeyUnlock = directDI.instance(),
    cryptoGenerator = directDI.instance(),
    showMessage = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingYubiKeyUnlockProvider(
    fingerprintReadRepository: FingerprintReadRepository,
    getVaultSession: GetVaultSession,
    enableYubiKeyUnlock: EnableYubiKeyUnlock,
    disableYubiKeyUnlock: DisableYubiKeyUnlock,
    cryptoGenerator: CryptoGenerator,
    showMessage: ShowMessage,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    fingerprintReadRepository.get(),
    getVaultSession(),
) { tokens, session ->
    if (tokens == null || session !is MasterSession.Key) {
        return@combine null
    }

    SettingIi(
        search = SettingIi.Search(
            group = "biometric",
            tokens = listOf(
                "yubikey",
                "unlock",
                "slot",
            ),
        ),
    ) {
        val context = LocalLeContext
        val translator = remember(context) {
            TranslatorScope.of(context)
        }

        val navigationController = LocalNavigationController.current
        val probeSink = remember {
            EventFlow<YubiKeyProvisionProbePrompt>()
        }
        val provisionSink = remember {
            EventFlow<YubiKeyProvisionPrompt>()
        }

        fun handleProvisioningFailure(exception: Throwable) {
            if (exception is YubiKeyAuthCanceledException) {
                return
            }

            windowCoroutineScope.launch {
                val readable = getErrorReadableMessage(
                    exception,
                    translator,
                )
                showMessage.copy(
                    ToastMessage(
                        type = ToastMessage.Type.ERROR,
                        title = readable.title,
                        text = readable.text,
                    ),
                )
            }
        }

        fun launchProvisioning(slot: Int, overwrite: Boolean) {
            val challenge = cryptoGenerator.seed(length = YUBIKEY_CHALLENGE_LENGTH)
            val secret = cryptoGenerator.seed(length = YUBIKEY_SECRET_LENGTH)
            val prompt = YubiKeyProvisionPrompt(
                slot = slot,
                challenge = challenge,
                secret = secret,
                overwrite = overwrite,
                onComplete = { result ->
                    result.fold(
                        ifLeft = ::handleProvisioningFailure,
                        ifRight = { response ->
                            enableYubiKeyUnlock(slot, challenge, response)
                                .launchIn(windowCoroutineScope)
                        },
                    )
                },
            )
            provisionSink.emit(prompt)
        }

        fun confirmProvisioning(slot: Int, overwrite: Boolean) {
            windowCoroutineScope.launch {
                val route = registerRouteResultReceiver(
                    route = ConfirmationRoute(
                        args = ConfirmationRoute.Args(
                            title = translator.translate(Res.string.pref_item_yubikey_unlock_confirm_title),
                            message = if (overwrite) {
                                translator.translate(
                                    Res.string.pref_item_yubikey_unlock_confirm_message_configured,
                                    slot,
                                )
                            } else {
                                translator.translate(
                                    Res.string.pref_item_yubikey_unlock_confirm_message_empty,
                                    slot,
                                )
                            },
                        ),
                    ),
                ) { result ->
                    if (result is ConfirmationResult.Confirm) {
                        launchProvisioning(slot, overwrite)
                    }
                }
                navigationController.queue(
                    NavigationIntent.NavigateToRoute(route),
                )
            }
        }

        fun launchEnrollment(slot: Int) {
            val prompt = YubiKeyProvisionProbePrompt(
                slot = slot,
                onComplete = { result ->
                    result.fold(
                        ifLeft = ::handleProvisioningFailure,
                        ifRight = { probe ->
                            confirmProvisioning(
                                slot = probe.slot,
                                overwrite = probe.isConfigured,
                            )
                        },
                    )
                },
            )
            probeSink.emit(prompt)
        }

        val dropdown = buildContextItems {
            section {
                for (slot in 1..YUBIKEY_SLOTS_COUNT) {
                    val action = FlatItemAction(
                        leading = {
                            Box(
                                modifier = Modifier
                                    .width(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = slot.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        title = TextHolder.Res(Res.string.pref_item_yubikey_unlock_provision_slot_title),
                        onClick = ::launchEnrollment
                            .partially1(slot),
                    )
                    this += action
                }
            }
            section {
                if (tokens.yubiKey == null) {
                    return@section
                }

                this += FlatItemAction(
                    title = Res.string.pref_item_yubikey_unlock_disable_title.wrap(),
                    onClick = {
                        disableYubiKeyUnlock()
                            .launchIn(windowCoroutineScope)
                    },
                )
            }
        }

        SettingYubiKeyUnlock(
            slot = tokens.yubiKey?.slot,
            dropdown = dropdown,
        )

        YubiKeyProvisionProbeEffect(probeSink)
        YubiKeyProvisionEffect(provisionSink)
    }
}

@Composable
private fun SettingYubiKeyUnlock(
    slot: Int?,
    dropdown: PersistentList<ContextItem>,
) {
    FlatDropdownSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Outlined.KeyguardYubiKey),
        content = {
            val text = when (slot) {
                null -> null
                else -> stringResource(
                    Res.string.pref_item_yubikey_unlock_text_on_slot_n,
                    slot.toString(),
                )
            }
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_yubikey_unlock_title),
                    )
                },
                text = if (text != null) {
                    // composable
                    {
                        Text(
                            text = text,
                        )
                    }
                } else {
                    null
                },
            )
        },
        dropdown = dropdown,
        trailing = {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            ) {
                Switch(
                    checked = slot != null,
                    enabled = true,
                    onCheckedChange = null,
                )
            }
        },
    )
}
