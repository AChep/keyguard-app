package com.artemchep.keyguard.feature.home.vault.search.sort

import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@LeParcelize
@Serializable
object LastModifiedSort : Sort, PureSort {
    private object LastModifiedRawComparator : Comparator<VaultItem2.Item> {
        override fun compare(
            a: VaultItem2.Item,
            b: VaultItem2.Item,
        ): Int = compareValues(b.revisionDate, a.revisionDate)
    }

    @LeIgnoredOnParcel
    @Transient
    override val id: String = "last_modified"

    @LeIgnoredOnParcel
    private val comparator = LastModifiedRawComparator
        .thenBy(AlphabeticalSort) { it }

    override fun compare(
        a: VaultItem2.Item,
        b: VaultItem2.Item,
    ): Int = comparator.compare(a, b)
}
