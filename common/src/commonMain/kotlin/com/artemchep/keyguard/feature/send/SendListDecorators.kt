package com.artemchep.keyguard.feature.send

import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.decorator.ItemDecorator
import com.artemchep.keyguard.feature.decorator.ItemDecoratorDate
import com.artemchep.keyguard.feature.decorator.ItemDecoratorNone
import com.artemchep.keyguard.feature.decorator.ItemDecoratorTitle
import com.artemchep.keyguard.feature.home.vault.util.AlphabeticalSortMinItemsSize
import com.artemchep.keyguard.feature.send.search.AlphabeticalSendSort
import com.artemchep.keyguard.feature.send.search.LastDeletedSendSort
import com.artemchep.keyguard.feature.send.search.LastExpiredSendSort
import com.artemchep.keyguard.feature.send.search.LastModifiedSendSort

/**
 * Picks the section decorator that matches the active sort order.
 * Shared between the phone/desktop and Wear OS send lists.
 */
fun createSendListSortDecorator(
    orderConfig: ComparatorHolder?,
    itemCount: Int,
    dateFormatter: DateFormatter,
): ItemDecorator<SendItem, SendItem.Item> = when {
    orderConfig?.comparator is AlphabeticalSendSort &&
            // it looks ugly on small lists
            itemCount >= AlphabeticalSortMinItemsSize ->
        ItemDecoratorTitle<SendItem, SendItem.Item>(
            selector = { it.title.text },
            factory = { id, text ->
                SendItem.Section(
                    id = id,
                    text = text,
                )
            },
        )

    orderConfig?.comparator is LastModifiedSendSort ->
        ItemDecoratorDate<SendItem, SendItem.Item>(
            dateFormatter = dateFormatter,
            selector = { it.revisionDate },
            factory = { id, text ->
                SendItem.Section(
                    id = id,
                    text = text,
                )
            },
        )

    orderConfig?.comparator is LastExpiredSendSort ->
        ItemDecoratorDate<SendItem, SendItem.Item>(
            dateFormatter = dateFormatter,
            selector = { it.source.expirationDate },
            factory = { id, text ->
                SendItem.Section(
                    id = id,
                    text = text,
                )
            },
        )

    orderConfig?.comparator is LastDeletedSendSort ->
        ItemDecoratorDate<SendItem, SendItem.Item>(
            dateFormatter = dateFormatter,
            selector = { it.source.deletedDate },
            factory = { id, text ->
                SendItem.Section(
                    id = id,
                    text = text,
                )
            },
        )

    else -> ItemDecoratorNone
}
