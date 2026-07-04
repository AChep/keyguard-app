package com.artemchep.keyguard.feature.home.settings.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.feature.localization.TextHolder
import kotlinx.collections.immutable.ImmutableList

data class NavigationItemsSettingsState(
    val items: ImmutableList<Item>,
    val availableItems: ImmutableList<AvailableItem>,
    val onReorder: (List<NavItemRef>) -> Unit,
    val onReset: () -> Unit,
) {
    data class Item(
        val key: String,
        val ref: NavItemRef,
        val title: TextHolder,
        val icon: ImageVector,
        val subIcon: ImageVector?,
        val visible: Boolean,
        val canMoveUp: Boolean,
        val canMoveDown: Boolean,
        val canRemove: Boolean,
        val onVisibilityToggle: (() -> Unit)?,
        val onMoveUp: (() -> Unit)?,
        val onMoveDown: (() -> Unit)?,
        val onRemove: (() -> Unit)?,
    )

    data class AvailableItem(
        val key: String,
        val ref: NavItemRef,
        val title: TextHolder,
        val text: TextHolder?,
        val icon: ImageVector,
        val onAdd: () -> Unit,
    )
}
