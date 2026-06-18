package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetSshAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.PutSshAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingSshAgentDisplayKeyNamesProvider(
    directDI: DirectDI,
) = settingSshAgentDisplayKeyNamesProvider(
    getSshAgentDisplayKeyNames = directDI.instance(),
    putSshAgentDisplayKeyNames = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingSshAgentDisplayKeyNamesProvider(
    getSshAgentDisplayKeyNames: GetSshAgentDisplayKeyNames,
    putSshAgentDisplayKeyNames: PutSshAgentDisplayKeyNames,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getSshAgentDisplayKeyNames().map { displayKeyNames ->
    if (CurrentPlatform.hasWatch()) {
        return@map null
    }

    val onCheckedChange = { shouldDisplayKeyNames: Boolean ->
        putSshAgentDisplayKeyNames(shouldDisplayKeyNames)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        platformClasses = listOf(
            Platform.Mobile.Android::class,
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "ssh",
                "git",
                "agent",
                "key",
                "name",
                "names",
                "display",
            ),
        ),
    ) {
        SettingSshAgentDisplayKeyNames(
            checked = displayKeyNames,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingSshAgentDisplayKeyNames(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Stub,
        title = {
            Text(
                text = stringResource(Res.string.pref_item_ssh_agent_display_key_names_title),
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
                    text = stringResource(Res.string.pref_item_ssh_agent_display_key_names_note),
                )
            }
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
