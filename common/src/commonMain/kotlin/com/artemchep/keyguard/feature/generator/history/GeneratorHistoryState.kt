@file:JvmName("GeneratorStateUtils")

package com.artemchep.keyguard.feature.generator.history

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class GeneratorHistoryState(
    val selection: Selection?,
    val options: ImmutableList<FlatItemAction>,
    val items: ImmutableList<GeneratorHistoryItem>,
)
