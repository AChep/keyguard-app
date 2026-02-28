package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.sshagent.filter.SshAgentFiltersRoute
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardCipherFilter
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingSshAgentFiltersProvider(
    directDI: DirectDI,
) = settingSshAgentFiltersProvider(
    getSshAgentFilter = directDI.instance(),
)

fun settingSshAgentFiltersProvider(
    getSshAgentFilter: GetSshAgentFilter,
): SettingComponent = getSshAgentFilter().map { filter ->
    val active = filter.normalize().isActive
    SettingIi(
        platformClasses = listOf(
            Platform.Desktop::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "ssh",
                "git",
                "agent",
                "filter",
                "key",
                "keys",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingSshAgentFilters(
            active = active,
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = SshAgentFiltersRoute,
                )
                navigationController.queue(intent)
            },
        )
    }
}

@Composable
private fun SettingSshAgentFilters(
    active: Boolean,
    onClick: (() -> Unit)?,
) {
    FlatItemSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Outlined.KeyguardCipherFilter),
        trailing = {
            ChevronIcon()
        },
        title = {
            Text(
                text = stringResource(Res.string.pref_item_ssh_agent_filters_title),
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (active) {
                            Res.string.pref_item_ssh_agent_filters_summary_active
                        } else {
                            Res.string.pref_item_ssh_agent_filters_summary_all
                        },
                    ),
                )
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )
                Text(
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    style = MaterialTheme.typography.bodySmall,
                    text = stringResource(Res.string.pref_item_ssh_agent_filters_text),
                )
            }
        },
        onClick = onClick,
    )
}

