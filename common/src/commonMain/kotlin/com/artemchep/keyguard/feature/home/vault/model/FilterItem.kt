package com.artemchep.keyguard.feature.home.vault.model

import androidx.compose.runtime.Composable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel

@optics
sealed interface FilterItem : FilterItemModel {
    companion object

    val sectionId: String

    data class Section(
        override val sectionId: String,
        override val text: String,
        override val expandable: Boolean = true,
        override val expanded: Boolean = true,
        override val onClick: (() -> Unit)?,
    ) : FilterItem, FilterItemModel.Section {
        companion object;

        override val id: String = sectionId
    }

    data class Item(
        override val sectionId: String,
        val filterSectionId: String,
        val filter: Filter,
        /**
         * Unique identifier of the set of
         * filters.
         */
        val filterId: String = filter.id,
        override val checked: Boolean,
        override val fill: Boolean,
        override val indent: Int = 0,
        override val leading: (@Composable () -> Unit)?,
        override val title: String,
        override val text: String?,
        override val textMaxLines: Int? = null,
        override val onClick: (() -> Unit)?,
        override val enabled: Boolean = onClick != null,
    ) : FilterItem, FilterItemModel.Item {
        companion object;

        override val id: String = "$sectionId|$filterSectionId|$filterId"

        sealed interface Filter {
            val id: String

            data class Toggle(
                val filters: Set<DFilter.Primitive>,
                override val id: String = filters
                    .joinToString(separator = ",") { it.key },
            ) : Filter

            data class Apply(
                val filters: Map<String, Set<DFilter.Primitive>>,
                override val id: String,
            ) : Filter
        }
    }
}
