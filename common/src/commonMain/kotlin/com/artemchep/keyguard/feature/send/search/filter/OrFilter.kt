package com.artemchep.keyguard.feature.send.search.filter

import arrow.core.partially1
import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.platform.parcelize.LeParcelize

@LeParcelize
data class OrSendFilter(
    val filters: List<SendFilter>,
    override val id: String = "or_filter:" + filters.joinToString(separator = ",") { it.id.orEmpty() },
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
        ::filte.partially1(a)
    }

    private fun filte(
        a: List<(SendItem.Item) -> Boolean>,
        item: SendItem.Item,
    ) = a.any { it(item) }
}
