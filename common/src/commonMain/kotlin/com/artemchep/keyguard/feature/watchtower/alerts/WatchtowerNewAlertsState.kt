@file:JvmName("GeneratorStateUtils")

package com.artemchep.keyguard.feature.watchtower.alerts

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlert
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import kotlinx.collections.immutable.ImmutableList
import java.util.*

@Immutable
data class WatchtowerNewAlertsState(
    val selection: Selection?,
    val options: ImmutableList<FlatItemAction>,
    val items: ImmutableList<Item>,
    val onMarkAllRead: () -> Unit,
) {
    sealed interface Item {
        val id: String

        @Immutable
        data class Alert(
            override val id: String,
            val item: VaultItem2.Item,
            val cipher: DSecret,
            val type: DWatchtowerAlertType,
            val alert: DWatchtowerAlert,
            val date: String,
            val read: Boolean,
        ) : Item

        @Immutable
        data class Section(
            override val id: String,
            val text: TextHolder? = null,
            val caps: Boolean = true,
        ) : Item
    }
}
