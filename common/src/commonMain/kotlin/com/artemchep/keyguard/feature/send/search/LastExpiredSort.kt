package com.artemchep.keyguard.feature.send.search

import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@LeParcelize
@Serializable
object LastExpiredSendSort : SendSort, PureSendSort {
    private object LastExpiredRawComparator : Comparator<SendItem.Item> {
        override fun compare(
            a: SendItem.Item,
            b: SendItem.Item,
        ): Int = compareValues(b.source.expirationDate, a.source.expirationDate)
    }

    @LeIgnoredOnParcel
    @Transient
    override val id: String = "last_expired"

    @LeIgnoredOnParcel
    private val comparator = LastExpiredRawComparator
        .thenBy(AlphabeticalSendSort) { it }

    override fun compare(
        a: SendItem.Item,
        b: SendItem.Item,
    ): Int = comparator.compare(a, b)
}
