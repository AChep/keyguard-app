package com.artemchep.keyguard.feature.home.vault.search.filter

import arrow.optics.Getter
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeParcelable

interface PureFilter : (List<Any>, (Any) -> VaultItem2.Item) -> (VaultItem2.Item) -> Boolean {
    companion object {
        operator fun invoke(
            value: String,
            getter: Getter<VaultItem2.Item, String>,
        ): PureFilter = object : PureFilter {
            override val id: String = value

            override fun invoke(
                list: List<Any>,
                getter: (Any) -> VaultItem2.Item,
            ): (VaultItem2.Item) -> Boolean = ::internalFilter

            private fun internalFilter(item: VaultItem2.Item) = getter.get(item) == value
        }
    }

    val id: String?
}

interface Filter : PureFilter, LeParcelable
