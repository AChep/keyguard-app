package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.vault.FingerprintReadRepository
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.DisableBiometric
import com.artemchep.keyguard.common.usecase.EnableBiometric
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingBiometricsProvider(
    koinScope: Scope,
) = settingBiometricsProvider(
    fingerprintReadRepository = koinScope.get(),
    biometricStatusUseCase = koinScope.get(),
    getBiometricRequireConfirmation = koinScope.get(),
    enableBiometric = koinScope.get(),
    disableBiometric = koinScope.get(),
    showMessage = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingBiometricsProvider(
    fingerprintReadRepository: FingerprintReadRepository,
    biometricStatusUseCase: BiometricStatusUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    enableBiometric: EnableBiometric,
    disableBiometric: DisableBiometric,
    showMessage: ShowMessage,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = biometricStatusUseCase()
    .map { it is BiometricStatus.Available }
    .distinctUntilChanged()
    .flatMapLatest { hasBiometrics ->
        if (hasBiometrics) {
            createSettingComponentFlow(
                fingerprintReadRepository = fingerprintReadRepository,
                getBiometricRequireConfirmation = getBiometricRequireConfirmation,
                enableBiometric = enableBiometric,
                disableBiometric = disableBiometric,
                showMessage = showMessage,
                windowCoroutineScope = windowCoroutineScope,
            )
        } else {
            // hide option if the device does not have biometrics
            flowOf(null)
        }
    }

private fun createSettingComponentFlow(
    fingerprintReadRepository: FingerprintReadRepository,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    enableBiometric: EnableBiometric,
    disableBiometric: DisableBiometric,
    showMessage: ShowMessage,
    windowCoroutineScope: WindowCoroutineScope,
) = combine(
    getBiometricRequireConfirmation(),
    fingerprintReadRepository.get()
        .map { tokens ->
            tokens?.biometric != null
        },
) { requireConfirmation, biometrics ->
    SettingIi(
        search = SettingIi.Search(
            group = "biometric",
            tokens = buildList {
                add("biometric")
                when (CurrentPlatform) {
                    is Platform.Desktop.Windows -> {
                        add("windows")
                        add("hello")
                    }

                    is Platform.Desktop.Linux -> {
                        add("linux")
                        add("polkit")
                        add("system")
                    }

                    else -> {
                        // Do nothing
                    }
                }
            },
        ),
    ) {
        val promptSink = remember {
            EventFlow<BiometricAuthPrompt>()
        }

        SettingBiometrics(
            checked = biometrics,
            onCheckedChange = { shouldBeChecked ->
                if (shouldBeChecked) {
                    enableBiometrics(
                        requireConfirmation = requireConfirmation,
                        enableBiometric = enableBiometric,
                        showMessage = showMessage,
                        windowCoroutineScope = windowCoroutineScope,
                        promptSink = promptSink,
                    )
                } else {
                    disableBiometric()
                        .launchIn(windowCoroutineScope)
                }
            },
        )

        BiometricPromptEffect(promptSink)
    }
}

private fun enableBiometrics(
    requireConfirmation: Boolean,
    enableBiometric: EnableBiometric,
    showMessage: ShowMessage,
    windowCoroutineScope: WindowCoroutineScope,
    promptSink: EventFlow<BiometricAuthPrompt>,
) {
    enableBiometric(null) // use global session
        .effectMap { biometric ->
            val cipher = biometric.getCipher()
            val prompt = BiometricAuthPrompt(
                title = TextHolder.Res(Res.string.pref_item_biometric_unlock_confirm_title),
                cipher = cipher,
                requireConfirmation = requireConfirmation,
                onComplete = { result ->
                    result.fold(
                        ifLeft = { exception ->
                            when (exception.code) {
                                BiometricAuthException.ERROR_CANCELED,
                                BiometricAuthException.ERROR_USER_CANCELED,
                                BiometricAuthException.ERROR_NEGATIVE_BUTTON,
                                    -> return@fold
                            }

                            showMessage.copy(
                                ToastMessage(
                                    type = ToastMessage.Type.ERROR,
                                    title = exception.message
                                        ?: exception.toString(),
                                ),
                            )
                        },
                        ifRight = {
                            biometric.getCreateIo()
                                .launchIn(windowCoroutineScope)
                        },
                    )
                },
            )
            promptSink.emit(prompt)
        }
        .launchIn(windowCoroutineScope)
}

@Composable
private fun SettingBiometrics(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    val title = when (CurrentPlatform) {
        is Platform.Desktop.Windows -> Res.string.pref_item_windows_hello_unlock_title
        is Platform.Desktop.Linux -> Res.string.pref_item_system_auth_unlock_title
        else -> Res.string.pref_item_biometric_unlock_title
    }
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.Fingerprint,
        title = stringResource(title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
