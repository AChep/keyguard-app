package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.service.vault.FingerprintReadRepository
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.GetBiometricTimeout
import com.artemchep.keyguard.common.usecase.GetBiometricTimeoutVariants
import com.artemchep.keyguard.common.usecase.PutBiometricTimeout
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.format
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

fun settingRequireMasterPasswordProvider(
    directDI: DirectDI,
) = settingRequireMasterPasswordProvider(
    fingerprintReadRepository = directDI.instance(),
    biometricStatusUseCase = directDI.instance(),
    getBiometricTimeout = directDI.instance(),
    getBiometricTimeoutVariants = directDI.instance(),
    putBiometricTimeout = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
    context = directDI.instance(),
)

fun settingRequireMasterPasswordProvider(
    fingerprintReadRepository: FingerprintReadRepository,
    biometricStatusUseCase: BiometricStatusUseCase,
    getBiometricTimeout: GetBiometricTimeout,
    getBiometricTimeoutVariants: GetBiometricTimeoutVariants,
    putBiometricTimeout: PutBiometricTimeout,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
): SettingComponent = biometricStatusUseCase()
    .map { it is BiometricStatus.Available }
    .distinctUntilChanged()
    .flatMapLatest { hasBiometrics ->
        if (hasBiometrics) {
            ah(
                fingerprintReadRepository = fingerprintReadRepository,
                getBiometricTimeout = getBiometricTimeout,
                getBiometricTimeoutVariants = getBiometricTimeoutVariants,
                putBiometricTimeout = putBiometricTimeout,
                windowCoroutineScope = windowCoroutineScope,
                context = context,
            )
        } else {
            // hide option if the device does not have biometrics
            flowOf(null)
        }
    }

private suspend fun ah(
    fingerprintReadRepository: FingerprintReadRepository,
    getBiometricTimeout: GetBiometricTimeout,
    getBiometricTimeoutVariants: GetBiometricTimeoutVariants,
    putBiometricTimeout: PutBiometricTimeout,
    windowCoroutineScope: WindowCoroutineScope,
    context: LeContext,
) = combine(
    getBiometricTimeout(),
    getBiometricTimeoutVariants()
        .combine(
            flow = fingerprintReadRepository.get()
                .map { tokens ->
                    tokens?.biometric != null
                },
        ) { variants, hasBiometricEnabled ->
            variants.takeIf { hasBiometricEnabled }.orEmpty()
        },
) { timeout, variants ->
    val text = getRequirePasswordDurationTitle(timeout, context)
    val dropdown = variants
        .map { duration ->
            val actionSelected = duration == timeout
            val actionTitle = getRequirePasswordDurationTitle(duration, context)
            FlatItemAction(
                title = TextHolder.Value(actionTitle),
                selected = actionSelected,
                onClick = {
                    putBiometricTimeout(duration)
                        .launchIn(windowCoroutineScope)
                },
            )
        }

    SettingIi(
        search = SettingIi.Search(
            group = "password",
            tokens = listOf(
                "master",
                "password",
            ),
        ),
    ) {
        SettingRequireMasterPassword(
            text = text,
            dropdown = dropdown,
        )
    }
}

private suspend fun getRequirePasswordDurationTitle(duration: Duration, context: LeContext) =
    when (duration) {
        Duration.ZERO -> textResource(
            Res.string.pref_item_require_app_password_immediately_text,
            context,
        )

        Duration.INFINITE -> textResource(
            Res.string.pref_item_require_app_password_never_text,
            context,
        )

        else -> duration.format(context)
    }

@Composable
fun SettingRequireMasterPassword(
    text: String,
    dropdown: List<FlatItemAction>,
) {
    FlatDropdownSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        content = {
            SettingRequireMasterPasswordContent(
                text = text,
            )
        },
        dropdown = dropdown,
    )
}

@Composable
private fun ColumnScope.SettingRequireMasterPasswordContent(
    text: String,
) {
    FlatItemTextContent(
        title = {
            Text(
                text = stringResource(Res.string.pref_item_require_app_password_title),
            )
        },
        text = {
            Text(text)
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            Text(
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                style = MaterialTheme.typography.bodySmall,
                text = stringResource(Res.string.pref_item_require_app_password_note),
            )
        },
    )
}
