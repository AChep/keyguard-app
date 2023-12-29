package com.artemchep.keyguard.feature.send.search

import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@LeParcelize
@Serializable
object AccessCountSendSort : SendSort {
    private object AccessCountRawComparator : Comparator<SendItem.Item> {
        override fun compare(
            a: SendItem.Item,
            b: SendItem.Item,
        ): Int = compareValues(b.source.accessCount, a.source.accessCount)
    }

    @LeIgnoredOnParcel
    @Transient
    override val id: String = "access_count"

    @LeIgnoredOnParcel
    private val comparator = AccessCountRawComparator
        .thenBy(AlphabeticalSendSort) { it }

    override fun compare(
        a: SendItem.Item,
        b: SendItem.Item,
    ): Int = comparator.compare(a, b)
}
