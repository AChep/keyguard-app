package com.artemchep.keyguard.feature.gpgagent.history

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class GpgAgentHistoryState(
    val subtitle: String?,
    val options: ImmutableList<FlatItemAction>,
    val items: ImmutableList<GpgAgentHistoryItem>,
)
