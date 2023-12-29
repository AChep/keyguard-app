package com.artemchep.keyguard.feature.search.sort.model

import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.feature.localization.TextHolder

interface SortItemModel {
    val id: String

    interface Section : SortItemModel {
        val text: TextHolder?
    }

    interface Item : SortItemModel {
        val icon: ImageVector?
        val title: TextHolder
        val checked: Boolean
        val onClick: (() -> Unit)?
    }
}
