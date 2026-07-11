package com.artemchep.keyguard.feature.agent

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.artemchep.keyguard.common.service.agent.AgentCallerIdentity

/**
 * Display-only application metadata resolved by the Keyguard process.
 *
 * Neither the label nor the icon participates in approval-cache equality.
 * Authorization is derived exclusively from [AgentCallerIdentity.authorization].
 */
internal data class AgentApplicationPresentation(
    val displayName: String? = null,
    val icon: ImageBitmap? = null,
)

/** Resolves an OS-owned application label and icon without blocking the UI. */
@Composable
internal expect fun rememberAgentApplicationPresentation(
    caller: AgentCallerIdentity?,
): AgentApplicationPresentation
