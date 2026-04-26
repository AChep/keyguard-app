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

        data class Section(
            override val id: String,
            val title: TextHolder?,
        ) : Item

        data class Action(
            override val id: String,
            val title: TextHolder,
            val icon: ImageVector? = null,
            val onClick: () -> Unit,
        ) : Item
    }
}
