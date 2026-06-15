package com.artemchep.keyguard.wear.feature.home

import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.feature.localization.TextHolder
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class WearHomeState(
    val headerItem: Item.Action? = null,
    val items: ImmutableList<Item> = persistentListOf(),
) {
    sealed interface Item {
        val id: String
        val contentType: String

        data class Section(
            override val id: String,
            val title: TextHolder?,
        ) : Item {
            override val contentType: String get() = "wear_home_section"
        }

        data class Action(
            override val id: String,
            val title: TextHolder,
            val icon: ImageVector? = null,
            val onClick: () -> Unit,
        ) : Item {
            override val contentType: String get() = "wear_home_action"
        }
    }
}
