package com.artemchep.keyguard.feature.search.filter.model

import androidx.compose.runtime.Composable

interface FilterItemModel {
    val id: String

    interface Section : FilterItemModel {
        val text: String
        val expandable: Boolean
        val expanded: Boolean
        val onClick: (() -> Unit)?
    }

    interface Item : FilterItemModel {
        val leading: (@Composable () -> Unit)?
        val title: String
        val text: String?
        val textMaxLines: Int?
        val checked: Boolean
        val enabled: Boolean
        val fill: Boolean
        val indent: Int
        val onClick: (() -> Unit)?
    }
}
