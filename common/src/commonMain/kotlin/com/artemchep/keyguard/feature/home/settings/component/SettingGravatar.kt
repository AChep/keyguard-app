package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetGravatar
import com.artemchep.keyguard.common.usecase.PutGravatar
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import compose.icons.FeatherIcons
import dev.icerock.moko.resources.compose.stringResource
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
    FlatItem(
        leading = {
            Icon(
                imageVector = Icons.Outlined.AccountCircle,
                contentDescription = null,
            )
        },
        trailing = {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentEnforcement provides false,
            ) {
                Switch(
                    checked = checked,
                    enabled = onCheckedChange != null,
                    onCheckedChange = onCheckedChange,
                )
            }
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_load_gravatar_icons_title),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
