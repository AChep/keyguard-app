package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.service.vault.FingerprintReadRepository
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.DisableBiometric
import com.artemchep.keyguard.common.usecase.EnableBiometric
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingBiometricsProvider(
    directDI: DirectDI,
) = settingBiometricsProvider(
    fingerprintReadRepository = directDI.instance(),
    biometricStatusUseCase = directDI.instance(),
    enableBiometric = directDI.instance(),
    disableBiometric = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingBiometricsProvider(
    fingerprintReadRepository: FingerprintReadRepository,
    biometricStatusUseCase: BiometricStatusUseCase,
    enableBiometric: EnableBiometric,
    disableBiometric: DisableBiometric,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = biometricStatusUseCase()
    .map { it is BiometricStatus.Available }
    .distinctUntilChanged()
    .flatMapLatest { hasBiometrics ->
        if (hasBiometrics) {
            fingerprintReadRepository.get()
                .map { tokens ->
                    val hasBiometricEnabled = tokens?.biometric != null

                    SettingIi(
                        search = SettingIi.Search(
                            group = "biometric",
                            tokens = listOf(
                                "biometric",
                            ),
                        ),
                    ) {
                        val promptSink = remember {
                            EventFlow<BiometricAuthPrompt>()
                        }

                        SettingBiometrics(
                            checked = hasBiometricEnabled,
                            onCheckedChange = { shouldBeChecked ->
                                if (shouldBeChecked) {
                                    enableBiometric(null) // use global session
                                        .map { d ->
                                            val cipher = d.getCipher()
                                            val prompt = BiometricAuthPrompt(
                                                title = TextHolder.Res(Res.strings.pref_item_biometric_unlock_confirm_title),
                                                cipher = cipher,
                                                onComplete = { result ->
                                                    result.fold(
                                                        ifLeft = { exception ->
                                                            val message = exception.message
                                                            // biometricPromptErrorSink.emit(message)
                                                        },
                                                        ifRight = {
                                                            d.getCreateIo()
                                                                .launchIn(windowCoroutineScope)
                                                        },
                                                    )
                                                },
                                            )
                                            promptSink.emit(prompt)
                                        }
                                        .launchIn(windowCoroutineScope)
                                } else {
                                    disableBiometric()
                                        .launchIn(windowCoroutineScope)
                                }
                            },
                        )

                        BiometricPromptEffect(promptSink)
                    }
                }
        } else {
            // hide option if the device does not have biometrics
            flowOf(null)
        }
    }

@Composable
private fun SettingBiometrics(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.Fingerprint),
        trailing = {
            Switch(
                checked = checked,
                enabled = onCheckedChange != null,
                onCheckedChange = onCheckedChange,
            )
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_biometric_unlock_title),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
