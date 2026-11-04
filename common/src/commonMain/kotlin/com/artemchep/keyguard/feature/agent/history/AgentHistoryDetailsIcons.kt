package com.artemchep.keyguard.feature.agent.history

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.ui.icons.KeyguardDecrypt
import com.artemchep.keyguard.ui.icons.KeyguardFileSign
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.theme.ok

/** The color of the response text in a request log row. */
@Composable
internal fun AgentHistoryOutcome.contentColor(): Color = when (this) {
    AgentHistoryOutcome.SUCCESS -> MaterialTheme.colorScheme.ok
    AgentHistoryOutcome.FAILURE -> MaterialTheme.colorScheme.error
    AgentHistoryOutcome.NEUTRAL -> MaterialTheme.colorScheme.onSurface
}

// A flat mapping of every icon, not branching logic.
@Suppress("CyclomaticComplexMethod")
internal fun AgentHistoryDetailsIcon.imageVector(): ImageVector = when (this) {
    AgentHistoryDetailsIcon.LIST_KEYS -> Icons.Outlined.Key
    AgentHistoryDetailsIcon.SSH_SIGN -> Icons.Outlined.KeyguardSshKey
    AgentHistoryDetailsIcon.GPG_SIGN -> Icons.Outlined.KeyguardFileSign
    AgentHistoryDetailsIcon.GPG_DECRYPT -> Icons.Outlined.KeyguardDecrypt
    AgentHistoryDetailsIcon.RUN -> Icons.Outlined.Terminal
    AgentHistoryDetailsIcon.MCP -> Icons.Outlined.SmartToy
    AgentHistoryDetailsIcon.ENV_FILE -> Icons.Outlined.Description
    AgentHistoryDetailsIcon.UNKNOWN -> Icons.Stub
    AgentHistoryDetailsIcon.APPLICATION -> Icons.Outlined.Apps
    AgentHistoryDetailsIcon.APPLICATION_PATH -> Icons.Outlined.Inventory2
    AgentHistoryDetailsIcon.PROCESS -> Icons.Outlined.Memory
    AgentHistoryDetailsIcon.EXECUTABLE -> Icons.Outlined.Terminal
    AgentHistoryDetailsIcon.PROCESS_ID -> Icons.Outlined.Tag
    AgentHistoryDetailsIcon.FINGERPRINT -> Icons.Outlined.Fingerprint
    AgentHistoryDetailsIcon.KEYGRIP -> Icons.Outlined.Key
    AgentHistoryDetailsIcon.CLIENT -> Icons.Outlined.Hub
    AgentHistoryDetailsIcon.ERROR -> Icons.Outlined.ErrorOutline
}
