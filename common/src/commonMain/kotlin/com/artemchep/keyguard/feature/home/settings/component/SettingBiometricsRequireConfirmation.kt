package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.service.vault.FingerprintReadRepository
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.PutBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingBiometricsRequireConfirmationProvider(
    directDI: DirectDI,
) = settingBiometricsRequireConfirmationProvider(
    fingerprintReadRepository = directDI.instance(),
    biometricStatusUseCase = directDI.instance(),
    getBiometricRequireConfirmation = directDI.instance(),
    putBiometricRequireConfirmation = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingBiometricsRequireConfirmationProvider(
    fingerprintReadRepository: FingerprintReadRepository,
    biometricStatusUseCase: BiometricStatusUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    putBiometricRequireConfirmation: PutBiometricRequireConfirmation,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = biometricStatusUseCase()
    .map { it is BiometricStatus.Available && CurrentPlatform is Platform.Mobile }
    .distinctUntilChanged()
    .flatMapLatest { hasBiometrics ->
        if (hasBiometrics) {
            createSettingComponentFlow(
                fingerprintReadRepository = fingerprintReadRepository,
                getBiometricRequireConfirmation = getBiometricRequireConfirmation,
                putBiometricRequireConfirmation = putBiometricRequireConfirmation,
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
    putBiometricRequireConfirmation: PutBiometricRequireConfirmation,
    windowCoroutineScope: WindowCoroutineScope,
) = combine(
    getBiometricRequireConfirmation(),
    fingerprintReadRepository.get()
        .map { tokens ->
            tokens?.biometric != null
        },
) { requireConfirmation, biometrics ->
    val onCheckedChange = { shouldRequireConfirmation: Boolean ->
        putBiometricRequireConfirmation(shouldRequireConfirmation)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "biometric",
            tokens = listOf(
                "biometric",
                "confirmation",
            ),
        ),
    ) {
        SettingBiometricsRequireConfirmation(
            checked = requireConfirmation,
            onCheckedChange = onCheckedChange.takeIf { biometrics },
        )
    }
}

@Composable
private fun SettingBiometricsRequireConfirmation(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        title = stringResource(Res.string.pref_item_biometric_unlock_require_confirmation_title),
        text = stringResource(Res.string.pref_item_biometric_unlock_require_confirmation_text),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
