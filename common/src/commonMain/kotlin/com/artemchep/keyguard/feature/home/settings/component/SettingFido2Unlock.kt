package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.Fido2UnlockAvailability
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.impl.Fido2UnlockService
import com.artemchep.keyguard.feature.fido2.Fido2PromptEffect
import com.artemchep.keyguard.feature.fido2.Fido2PromptHost
import com.artemchep.keyguard.feature.fido2.asFido2AppException
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

@Suppress("TooGenericExceptionCaught") // Display readable enrollment errors at the UI boundary.
fun settingFido2UnlockProvider(koinScope: Scope): SettingComponent {
    if (!koinScope.get<Fido2UnlockAvailability>().isSupported() || CurrentPlatform.hasWatch())
        return flowOf(null)
    val service = koinScope.get<Fido2UnlockService>()
    val showMessage = koinScope.get<ShowMessage>()
    return combine(
        koinScope.get<FingerprintReadWriteRepository>().get(),
        koinScope.get<GetVaultSession>()(),
    ) { tokens, session ->
        if (tokens == null || session !is MasterSession.Key) return@combine null
        SettingIi(
            platformClasses = listOf(Platform.Mobile.Android::class, Platform.Desktop::class)
        ) {
            val host = remember { Fido2PromptHost() }
            val scope = rememberCoroutineScope()
            val context = LocalLeContext
            var busy by remember { mutableStateOf(false) }
            LocalSettingPaneComponents.current.KgSwitch(
                icon = Icons.Outlined.Key,
                title = stringResource(Res.string.fido2_unlock_title),
                text = stringResource(Res.string.fido2_unlock_description),
                checked = tokens.fido2 != null,
                onCheckedChange =
                    if (busy) null
                    else
                        { enabled ->
                            busy = true
                            scope.launch {
                                try {
                                    (if (enabled) service.enroll(host::execute)
                                        else service.disable())
                                        .bind()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    val readable =
                                        getErrorReadableMessage(
                                            error.asFido2AppException(),
                                            TranslatorScope.of(context),
                                        )
                                    showMessage.copy(
                                        ToastMessage(
                                            type = ToastMessage.Type.ERROR,
                                            title = readable.title,
                                            text = readable.text,
                                        )
                                    )
                                } finally {
                                    busy = false
                                }
                            }
                        },
            )
            Fido2PromptEffect(host.events)
        }
    }
}
