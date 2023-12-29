package com.artemchep.keyguard.feature.send.search

import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@LeParcelize
@Serializable
object LastDeletedSendSort : SendSort, PureSendSort {
    private object LastDeletedRawComparator : Comparator<SendItem.Item> {
        override fun compare(
            a: SendItem.Item,
            b: SendItem.Item,
        ): Int = compareValues(b.source.deletedDate, a.source.deletedDate)
    }

    @LeIgnoredOnParcel
    @Transient
    override val id: String = "last_deleted"

    @LeIgnoredOnParcel
    private val comparator = LastDeletedRawComparator
        .thenBy(AlphabeticalSendSort) { it }

    override fun compare(
        a: SendItem.Item,
        b: SendItem.Item,
    ): Int = comparator.compare(a, b)
}
