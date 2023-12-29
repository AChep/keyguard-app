package com.artemchep.keyguard.feature.home.vault.search.filter

import arrow.core.partially1
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeParcelize

@LeParcelize
data class OrFilter(
    val filters: List<Filter>,
    override val id: String = "or_filter:" + filters.joinToString(separator = ",") { it.id.orEmpty() },
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
        ::filte.partially1(a)
    }

    private fun filte(
        a: List<(VaultItem2.Item) -> Boolean>,
        item: VaultItem2.Item,
    ) = a.any { it(item) }
}
