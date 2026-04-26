package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetGravatar
import com.artemchep.keyguard.common.usecase.PutGravatar
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingGravatarProvider(
    directDI: DirectDI,
) = settingGravatarProvider(
    getGravatar = directDI.instance(),
    putGravatar = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingGravatarProvider(
    getGravatar: GetGravatar,
    putGravatar: PutGravatar,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getGravatar().map { gravatar ->
    val onCheckedChange = { shouldBeEnabled: Boolean ->
        putGravatar(shouldBeEnabled)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "ui",
            tokens = listOf(
                "gravatar",
            ),
        ),
    ) {
        SettingGravatar(
            checked = gravatar,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingGravatar(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.AccountCircle,
        title = stringResource(Res.string.pref_item_load_gravatar_icons_title),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
