package com.artemchep.keyguard.feature.home.vault.search.filter

import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeParcelize

@LeParcelize
data class AndFilter(
    val filters: List<Filter>,
    override val id: String = "and_filter:" + filters.joinToString(separator = ",") { it.id.orEmpty() },
) : Filter, PureFilter {
    override fun invoke(
        list: List<Any>,
        getter: (Any) -> VaultItem2.Item,
    ): (VaultItem2.Item) -> Boolean = kotlin.run {
        val a = filters
            .map { filter ->
                filter.invoke(list, getter)
            }

        // lambda
        fun(
            item: VaultItem2.Item,
        ): Boolean = a.all { it(item) }
    }
}
