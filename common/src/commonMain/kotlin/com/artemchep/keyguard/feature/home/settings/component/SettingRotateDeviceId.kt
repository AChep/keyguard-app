package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.RotateDeviceIdUseCase
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingRotateDeviceId(
    directDI: DirectDI,
) = settingRotateDeviceId(
    rotateDeviceIdUseCase = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
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
    FlatItemSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Outlined.Key),
        title = {
            Text("Rotate device id")
        },
        text = {
            val msg = "Removes all added accounts & rotates the device identifier."
            Text(msg)
        },
        onClick = onClick,
    )
}
