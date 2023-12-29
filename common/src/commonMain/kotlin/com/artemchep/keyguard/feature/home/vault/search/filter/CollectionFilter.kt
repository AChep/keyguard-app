package com.artemchep.keyguard.feature.home.vault.search.filter

import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeParcelize

@LeParcelize
data class CollectionFilter(
    override val id: String?,
) : Filter, PureFilter {
    override fun invoke(
        list: List<Any>,
        getter: (Any) -> VaultItem2.Item,
    ): (VaultItem2.Item) -> Boolean = ::internalFilter

    private fun internalFilter(item: VaultItem2.Item) = if (id != null) {
        id in item.source.collectionIds
    } else {
        item.source.collectionIds.isEmpty()
    }
}
