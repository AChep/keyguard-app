package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_agent_status_failed
import com.artemchep.keyguard.res.pref_item_agent_status_stopped
import com.artemchep.keyguard.res.pref_item_agent_status_unsupported
import com.artemchep.keyguard.res.status_ready
import com.artemchep.keyguard.res.status_starting
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.ok
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AgentStatusText(
    status: AgentStatus,
) {
    val statusColor = when (status) {
        AgentStatus.Unsupported,
        AgentStatus.Stopped,
        -> LocalContentColor.current
            .combineAlpha(DisabledEmphasisAlpha)

        AgentStatus.Starting -> MaterialTheme.colorScheme.info
        AgentStatus.Ready -> MaterialTheme.colorScheme.ok
        AgentStatus.Failed -> MaterialTheme.colorScheme.error
    }
    val statusText = stringResource(
        when (status) {
            AgentStatus.Unsupported -> Res.string.pref_item_agent_status_unsupported
            AgentStatus.Stopped -> Res.string.pref_item_agent_status_stopped
            AgentStatus.Starting -> Res.string.status_starting
            AgentStatus.Ready -> Res.string.status_ready
            AgentStatus.Failed -> Res.string.pref_item_agent_status_failed
        },
    )
    Text(
        color = statusColor,
        text = statusText,
    )
}
