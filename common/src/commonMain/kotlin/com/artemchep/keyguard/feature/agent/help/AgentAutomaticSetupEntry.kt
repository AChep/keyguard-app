package com.artemchep.keyguard.feature.agent.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.agent_auto_setup_configure_action
import com.artemchep.keyguard.res.agent_auto_setup_enable_required
import com.artemchep.keyguard.res.agent_auto_setup_intro
import com.artemchep.keyguard.res.agent_auto_setup_title
import com.artemchep.keyguard.res.agent_auto_setup_unavailable
import com.artemchep.keyguard.res.agent_auto_setup_unavailable_flatpak
import com.artemchep.keyguard.res.agent_auto_setup_undo_action
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.tooltip.Tooltip
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
internal fun rememberAgentSetupEnabled(agent: SetupAgent): Flow<Boolean> {
    val getEnabled: () -> Flow<Boolean> = when (agent) {
        SetupAgent.SSH -> koinInject<GetSshAgent>()
        SetupAgent.GPG -> koinInject<GetGpgAgent>()
    }
    return remember(getEnabled) { getEnabled() }
}

@Composable
internal fun agentSetupEnableMessage(agent: SetupAgent): String = stringResource(
    Res.string.agent_auto_setup_enable_required,
    stringResource(agent.protocol),
)

private const val AGENT_SETUP_CLI_COMMAND = "keyguard agents setup"

@Composable
internal fun ColumnScope.AgentAutomaticSetup(agent: SetupAgent) {
    if (CurrentPlatform is Platform.Desktop) AgentSetupEntry(agent)
}

@Composable
internal fun ColumnScope.AgentTermuxAutomaticSetup(agent: SetupAgent) {
    AgentSetupEntry(agent)
}

@Composable
private fun AgentSetupEntry(agent: SetupAgent) {
    val available = rememberAgentSetupRunner(agent) != null
    val enabled by rememberAgentSetupEnabled(agent).collectAsState(initial = false)
    val navigation by rememberUpdatedState(LocalNavigationController.current)
    AgentSetupIntro(
        available = available,
        enabled = enabled,
        disabledMessage = agentSetupEnableMessage(agent),
        flatpak = (CurrentPlatform as? Platform.Desktop.Linux)?.isFlatpak == true,
        onConfigure = {
            if (enabled) navigation.queue(NavigationIntent.NavigateToRoute(AgentAutomaticSetupRoute(agent)))
        },
        onUndo = {
            navigation.queue(NavigationIntent.NavigateToRoute(AgentAutomaticSetupRoute(agent, AgentSetupMode.UNDO)))
        },
    )
    AgentSetupSectionDivider()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentSetupIntro(
    available: Boolean,
    enabled: Boolean,
    disabledMessage: String,
    flatpak: Boolean = false,
    onConfigure: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = Dimens.horizontalPadding)
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.extraLarge)
            .padding(horizontal = Dimens.horizontalPadding, vertical = Dimens.verticalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.verticalPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar { Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.primary) }
            if (available) {
                val undoLabel = stringResource(Res.string.agent_auto_setup_undo_action)
                Tooltip(valueOrNull = undoLabel, tooltip = { Text(it) }) {
                    IconButton(onClick = onUndo) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = undoLabel)
                    }
                }
            }
        }
        Text(stringResource(Res.string.agent_auto_setup_title), style = MaterialTheme.typography.titleMedium)
        val detail = when {
            available -> stringResource(Res.string.agent_auto_setup_intro)
            flatpak -> stringResource(Res.string.agent_auto_setup_unavailable_flatpak, AGENT_SETUP_CLI_COMMAND)
            else -> stringResource(Res.string.agent_auto_setup_unavailable)
        }
        Text(
            if (available && !enabled) disabledMessage else detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            shapes = ButtonDefaults.shapes(),
            enabled = available && enabled,
            onClick = onConfigure,
        ) { Text(stringResource(Res.string.agent_auto_setup_configure_action)) }
    }
}
