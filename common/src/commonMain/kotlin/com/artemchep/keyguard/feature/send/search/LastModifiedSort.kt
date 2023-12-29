package com.artemchep.keyguard.feature.send.search

import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@LeParcelize
@Serializable
object LastModifiedSendSort : SendSort, PureSendSort {
    private object LastModifiedRawComparator : Comparator<SendItem.Item> {
        override fun compare(
            a: SendItem.Item,
            b: SendItem.Item,
        ): Int = compareValues(b.revisionDate, a.revisionDate)
    }

    @LeIgnoredOnParcel
    @Transient
    override val id: String = "last_modified"

    @LeIgnoredOnParcel
    private val comparator = LastModifiedRawComparator
        .thenBy(AlphabeticalSendSort) { it }

    override fun compare(
        a: SendItem.Item,
        b: SendItem.Item,
    ): Int = comparator.compare(a, b)
}
