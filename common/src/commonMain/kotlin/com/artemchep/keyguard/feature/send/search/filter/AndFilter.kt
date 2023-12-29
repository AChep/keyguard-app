package com.artemchep.keyguard.feature.send.search.filter

import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.platform.parcelize.LeParcelize

@LeParcelize
data class AndSendFilter(
    val filters: List<SendFilter>,
    override val id: String = "and_filter:" + filters.joinToString(separator = ",") { it.id.orEmpty() },
) : SendFilter, PureSendFilter {
    override fun invoke(
        list: List<Any>,
        getter: (Any) -> SendItem.Item,
    ): (SendItem.Item) -> Boolean = kotlin.run {
        val a = filters
            .map { filter ->
                filter.invoke(list, getter)
            }

        // lambda
        fun(
            item: SendItem.Item,
        ): Boolean = a.all { it(item) }
    }
}
