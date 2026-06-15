package com.artemchep.keyguard.feature.sshagent.history

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class SshAgentHistoryState(
    val subtitle: String?,
    val options: ImmutableList<FlatItemAction>,
    val items: ImmutableList<SshAgentHistoryItem>,
)
