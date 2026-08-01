package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetGpgAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.PutGpgAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingGpgAgentDisplayKeyNamesProvider(
    directDI: DirectDI,
) = settingGpgAgentDisplayKeyNamesProvider(
    getGpgAgentDisplayKeyNames = directDI.instance(),
    putGpgAgentDisplayKeyNames = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingGpgAgentDisplayKeyNamesProvider(
    getGpgAgentDisplayKeyNames: GetGpgAgentDisplayKeyNames,
    putGpgAgentDisplayKeyNames: PutGpgAgentDisplayKeyNames,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getGpgAgentDisplayKeyNames().map { displayKeyNames ->
    val onCheckedChange = { shouldDisplayKeyNames: Boolean ->
        putGpgAgentDisplayKeyNames(shouldDisplayKeyNames)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        platformClasses = listOf(
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
            Platform.Desktop.Windows::class,
            Platform.Desktop.Other::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "gpg",
                "gnupg",
                "agent",
                "key",
                "name",
                "display",
            ),
        ),
    ) {
        SettingGpgAgentDisplayKeyNames(
            checked = displayKeyNames,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingGpgAgentDisplayKeyNames(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.Key,
        title = {
            Text(
                text = stringResource(Res.string.pref_item_gpg_agent_display_key_names_title),
            )
        },
        text = {
            Column {
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )
                Text(
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    style = MaterialTheme.typography.bodySmall,
                    text = stringResource(Res.string.pref_item_gpg_agent_display_key_names_note),
                )
            }
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
