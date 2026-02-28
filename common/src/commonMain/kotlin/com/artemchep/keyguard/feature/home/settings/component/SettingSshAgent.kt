package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.SshAgentStatus
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.common.usecase.GetSshAgentStatus
import com.artemchep.keyguard.common.usecase.PutSshAgent
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.ok
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingSshAgentProvider(
    directDI: DirectDI,
) = settingSshAgentProvider(
    getSshAgent = directDI.instance(),
    getSshAgentStatus = directDI.instance(),
    putSshAgent = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingSshAgentProvider(
    getSshAgent: GetSshAgent,
    getSshAgentStatus: GetSshAgentStatus,
    putSshAgent: PutSshAgent,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    getSshAgent(),
    getSshAgentStatus(),
) { sshAgent, sshAgentStatus ->
    val onCheckedChange = { shouldSshAgent: Boolean ->
        putSshAgent(shouldSshAgent)
            .launchIn(windowCoroutineScope)
        Unit
    }

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
            ),
        ),
    ) {
        SettingSshAgent(
            checked = sshAgent,
            status = sshAgentStatus,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingSshAgent(
    checked: Boolean,
    status: SshAgentStatus,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItemSimpleExpressive(
        shapeState = LocalSettingItemShape.current,
        leading = icon<RowScope>(Icons.Outlined.KeyguardSshKey),
        trailing = {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
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
                text = stringResource(Res.string.pref_item_ssh_agent_title),
            )
        },
        text = {
            val statusColor = when (status) {
                SshAgentStatus.Unsupported,
                SshAgentStatus.Stopped,
                -> LocalContentColor.current.combineAlpha(DisabledEmphasisAlpha)

                SshAgentStatus.Starting -> MaterialTheme.colorScheme.info
                SshAgentStatus.Ready -> MaterialTheme.colorScheme.ok
                SshAgentStatus.Failed -> MaterialTheme.colorScheme.error
            }
            Text(
                color = statusColor,
                text = stringResource(
                    when (status) {
                        SshAgentStatus.Unsupported -> Res.string.pref_item_ssh_agent_status_unsupported
                        SshAgentStatus.Stopped -> Res.string.pref_item_ssh_agent_status_stopped
                        SshAgentStatus.Starting -> Res.string.pref_item_ssh_agent_status_starting
                        SshAgentStatus.Ready -> Res.string.pref_item_ssh_agent_status_ready
                        SshAgentStatus.Failed -> Res.string.pref_item_ssh_agent_status_failed
                    },
                ),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
