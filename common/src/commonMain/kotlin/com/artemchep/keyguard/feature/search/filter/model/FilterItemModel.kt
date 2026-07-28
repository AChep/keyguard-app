package com.artemchep.keyguard.feature.search.filter.model

import androidx.compose.runtime.Composable

interface FilterItemModel {
    val id: String

    interface Section : FilterItemModel {
        enum class Layout {
            Flow,
            List,
        }

        val text: String
        val expandable: Boolean
        val expanded: Boolean
        val layout: Layout
            get() = Layout.Flow
        val onClick: (() -> Unit)?
    }

    interface Item {
        val leading: (@Composable () -> Unit)?
        val title: String
        val text: String?
        val textMaxLines: Int?
        val checked: Boolean
        val enabled: Boolean
    }

    interface ChipItem : FilterItemModel, Item {
        val onClick: (() -> Unit)?
    }

    interface ListItem : FilterItemModel, Item {
        val sectionId: String
        val nodeId: String
        val parentNodeId: String?
        val depth: Int
        val expandable: Boolean
        val onClick: (() -> Unit)?
    }
}
