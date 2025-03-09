package com.artemchep.keyguard.feature.send.search

import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@LeParcelize
@Serializable
object AlphabeticalSendSort : SendSort {
    @LeIgnoredOnParcel
    @Transient
    override val id: String = "alphabetical"

    override fun compare(
        a: SendItem.Item,
        b: SendItem.Item,
    ): Int = kotlin.run {
        val aTitle = a.title.text
        val bTitle = b.title.text
        AlphabeticalSort.compareStr(aTitle, bTitle)
    }
}
