package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.RotateDeviceIdUseCase
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.koin.core.scope.Scope

fun settingRotateDeviceId(
    koinScope: Scope,
) = settingRotateDeviceId(
    rotateDeviceIdUseCase = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingRotateDeviceId(
    rotateDeviceIdUseCase: RotateDeviceIdUseCase,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = flow {
    val onClick = {
        rotateDeviceIdUseCase()
            .launchIn(windowCoroutineScope)
        Unit
    }

    val state = if (!isRelease) {
        SettingIi {
            SettingRotateDeviceId(
                onClick = onClick,
            )
        }
    } else {
        null
    }
    emit(state)
    delay(1000L)
    emit(state)
    emit(state)
}

@Composable
private fun SettingRotateDeviceId(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Key,
        title = "Rotate device id",
        text = "Removes all added accounts & rotates the device identifier.",
        onClick = onClick,
    )
}
