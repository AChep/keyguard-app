package com.artemchep.keyguard.feature.agent

import androidx.compose.runtime.Immutable

/** One item that an approval prompt offers to pick, ready to display. */
@Immutable
data class AgentApprovalChoiceItem(
    val title: String,
    val text: String?,
)
