package com.artemchep.keyguard.feature.send

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.send.search.AccessCountSendSort
import com.artemchep.keyguard.feature.send.search.AlphabeticalSendSort
import com.artemchep.keyguard.feature.send.search.LastDeletedSendSort
import com.artemchep.keyguard.feature.send.search.LastExpiredSendSort
import com.artemchep.keyguard.feature.send.search.LastModifiedSendSort
import com.artemchep.keyguard.feature.send.search.SendSort
import com.artemchep.keyguard.feature.send.search.SendSortItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.KeyguardView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource

/**
 * Builds the list of sort options for the send list, marking the
 * option that matches the current value of [sortSink] as checked.
 * Shared between the phone/desktop and Wear OS send lists.
 */
fun createSendSortItemsFlow(
    sortSink: MutableStateFlow<ComparatorHolder>,
): Flow<List<SendSortItem>> {
    val groups = createSendSortItemGroups(sortSink)
    return sortSink
        .map { orderConfig -> groups.toSortItems(orderConfig) }
}

private class SortItemGroup(
    val item: SendSortItem.Item,
    val subItems: List<SendSortItem.Item>,
)

private fun createSendSortItemGroups(
    sortSink: MutableStateFlow<ComparatorHolder>,
): Map<SendSort, SortItemGroup> = mapOf(
    AlphabeticalSendSort to createSortItemGroup(
        sortSink = sortSink,
        comparator = AlphabeticalSendSort,
        id = "title",
        icon = Icons.Outlined.SortByAlpha,
        title = Res.string.sortby_title_title,
        normalTitle = Res.string.sortby_title_normal_mode,
        reverseTitle = Res.string.sortby_title_reverse_mode,
        favourites = true,
    ),
    AccessCountSendSort to createSortItemGroup(
        sortSink = sortSink,
        comparator = AccessCountSendSort,
        id = "access_count",
        icon = Icons.Outlined.KeyguardView,
        title = Res.string.sortby_access_count_title,
        normalTitle = Res.string.sortby_access_count_normal_mode,
        reverseTitle = Res.string.sortby_access_count_reverse_mode,
    ),
    LastModifiedSendSort to createSortItemGroup(
        sortSink = sortSink,
        comparator = LastModifiedSendSort,
        id = "modify_date",
        icon = Icons.Outlined.CalendarMonth,
        title = Res.string.sortby_modification_date_title,
        normalTitle = Res.string.sortby_modification_date_normal_mode,
        reverseTitle = Res.string.sortby_modification_date_reverse_mode,
    ),
    LastExpiredSendSort to createSortItemGroup(
        sortSink = sortSink,
        comparator = LastExpiredSendSort,
        id = "expiration_date",
        icon = Icons.Outlined.CalendarMonth,
        title = Res.string.sortby_expiration_date_title,
        normalTitle = Res.string.sortby_expiration_date_normal_mode,
        reverseTitle = Res.string.sortby_expiration_date_reverse_mode,
    ),
    LastDeletedSendSort to createSortItemGroup(
        sortSink = sortSink,
        comparator = LastDeletedSendSort,
        id = "deletion_date",
        icon = Icons.Outlined.CalendarMonth,
        title = Res.string.sortby_deletion_date_title,
        normalTitle = Res.string.sortby_deletion_date_normal_mode,
        reverseTitle = Res.string.sortby_deletion_date_reverse_mode,
    ),
)

/**
 * Creates a main sort option plus its normal and reversed
 * sub-options.
 */
private fun createSortItemGroup(
    sortSink: MutableStateFlow<ComparatorHolder>,
    comparator: SendSort,
    id: String,
    icon: ImageVector,
    title: StringResource,
    normalTitle: StringResource,
    reverseTitle: StringResource,
    favourites: Boolean = false,
): SortItemGroup {
    fun createComparatorAction(
        id: String,
        title: StringResource,
        icon: ImageVector? = null,
        config: ComparatorHolder,
    ) = SendSortItem.Item(
        id = id,
        config = config,
        title = TextHolder.Res(title),
        icon = icon,
        onClick = {
            sortSink.value = config
        },
        checked = false,
    )

    val normal = ComparatorHolder(
        comparator = comparator,
        favourites = favourites,
    )
    val reversed = ComparatorHolder(
        comparator = comparator,
        reversed = true,
        favourites = favourites,
    )
    return SortItemGroup(
        item = createComparatorAction(
            id = id,
            icon = icon,
            title = title,
            config = normal,
        ),
        subItems = listOf(
            createComparatorAction(
                id = "${id}_normal",
                title = normalTitle,
                config = normal,
            ),
            createComparatorAction(
                id = "${id}_rev",
                title = reverseTitle,
                config = reversed,
            ),
        ),
    )
}

private fun Map<SendSort, SortItemGroup>.toSortItems(
    orderConfig: ComparatorHolder,
): List<SendSortItem> {
    val mainItems = values
        .map { it.item }
        .map { item ->
            val checked = item.config.comparator == orderConfig.comparator
            item.copy(checked = checked)
        }
    val subItems = get(orderConfig.comparator)?.subItems.orEmpty()
        .map { item ->
            val checked = item.config == orderConfig
            item.copy(checked = checked)
        }

    val out = mutableListOf<SendSortItem>()
    out += mainItems
    if (subItems.isNotEmpty()) {
        out += SendSortItem.Section(
            id = "sub_items_section",
            text = TextHolder.Res(Res.string.options),
        )
        out += subItems
    }
    return out
}
