package com.artemchep.keyguard.feature.send.search.filter

import arrow.optics.Getter
import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.platform.parcelize.LeParcelable

interface PureSendFilter : (List<Any>, (Any) -> SendItem.Item) -> (SendItem.Item) -> Boolean {
    companion object {
        operator fun invoke(
            value: String,
            getter: Getter<SendItem.Item, String>,
        ): PureSendFilter = object : PureSendFilter {
            override val id: String = value

            override fun invoke(
                list: List<Any>,
                getter: (Any) -> SendItem.Item,
            ): (SendItem.Item) -> Boolean = ::internalFilter

            private fun internalFilter(item: SendItem.Item) = getter.get(item) == value
        }
    }

    val id: String?
}

interface SendFilter : PureSendFilter, LeParcelable
