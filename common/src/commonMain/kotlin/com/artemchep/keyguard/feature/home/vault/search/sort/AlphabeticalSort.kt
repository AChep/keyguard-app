package com.artemchep.keyguard.feature.home.vault.search.sort

import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@LeParcelize
@Serializable
object AlphabeticalSort : Sort, PureSort {
    @LeIgnoredOnParcel
    @Transient
    override val id: String = "alphabetical"

    override fun compare(
        a: VaultItem2.Item,
        b: VaultItem2.Item,
    ): Int = kotlin.run {
        val aTitle = a.title.text
        val bTitle = b.title.text
        aTitle.compareTo(bTitle, ignoreCase = true)
    }
}
