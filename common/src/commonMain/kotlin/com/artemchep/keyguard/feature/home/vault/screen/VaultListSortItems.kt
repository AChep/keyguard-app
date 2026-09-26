package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.feature.home.vault.model.SortItem
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.home.vault.search.sort.LastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordLastModifiedSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordSort
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordStrengthSort
import com.artemchep.keyguard.feature.home.vault.search.sort.Sort
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource

/**
 * Builds the list of sort options for the vault list, marking the
 * option that matches the current value of [sortSink] as checked.
 * Shared between the phone/desktop and Wear OS vault lists.
 */
fun createVaultSortItemsFlow(
    sortSink: MutableStateFlow<ComparatorHolder>,
): Flow<List<SortItem>> {
    val groups = createVaultSortItemGroups(sortSink)
    return sortSink
        .map { orderConfig -> groups.toSortItems(orderConfig) }
}

private class SortItemGroup(
    val item: SortItem.Item,
    val subItems: List<SortItem.Item>,
)

private fun createVaultSortItemGroups(
    sortSink: MutableStateFlow<ComparatorHolder>,
): Map<Sort, SortItemGroup> = mapOf(
    AlphabeticalSort to createSortItemGroup(
        sortSink = sortSink,
        comparator = AlphabeticalSort,
        id = "title",
        icon = Icons.Outlined.SortByAlpha,
        title = Res.string.sortby_title_title,
        normalTitle = Res.string.sortby_title_normal_mode,
        reverseTitle = Res.string.sortby_title_reverse_mode,
        favourites = true,
    ),
    LastModifiedSort to createSortItemGroup(
        sortSink = sortSink,
        comparator = LastModifiedSort,
        id = "modify_date",
        icon = Icons.Outlined.CalendarMonth,
        title = Res.string.sortby_modification_date_title,
        normalTitle = Res.string.sortby_modification_date_normal_mode,
        reverseTitle = Res.string.sortby_modification_date_reverse_mode,
    ),
    PasswordSort to createSortItemGroup(
        sortSink = sortSink,
        comparator = PasswordSort,
        id = "password",
        icon = Icons.Outlined.Password,
        title = Res.string.sortby_password_title,
        normalTitle = Res.string.sortby_password_normal_mode,
        reverseTitle = Res.string.sortby_password_reverse_mode,
    ),
    PasswordLastModifiedSort to createSortItemGroup(
        sortSink = sortSink,
        comparator = PasswordLastModifiedSort,
        id = "password_last_modified_strength",
        subId = "password_last_modified",
        icon = Icons.Outlined.CalendarMonth,
        title = Res.string.sortby_password_modification_date_title,
        normalTitle = Res.string.sortby_password_modification_date_normal_mode,
        reverseTitle = Res.string.sortby_password_modification_date_reverse_mode,
    ),
    PasswordStrengthSort to createSortItemGroup(
        sortSink = sortSink,
        comparator = PasswordStrengthSort,
        id = "password_strength",
        icon = Icons.Outlined.Security,
        title = Res.string.sortby_password_strength_title,
        normalTitle = Res.string.sortby_password_strength_normal_mode,
        reverseTitle = Res.string.sortby_password_strength_reverse_mode,
    ),
)

/**
 * Creates a main sort option plus its normal and reversed
 * sub-options. [subId] is the prefix of the sub-option ids and
 * defaults to [id].
 */
private fun createSortItemGroup(
    sortSink: MutableStateFlow<ComparatorHolder>,
    comparator: Sort,
    id: String,
    subId: String = id,
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
    ) = SortItem.Item(
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
                id = "${subId}_normal",
                title = normalTitle,
                config = normal,
            ),
            createComparatorAction(
                id = "${subId}_rev",
                title = reverseTitle,
                config = reversed,
            ),
        ),
    )
}

private fun Map<Sort, SortItemGroup>.toSortItems(
    orderConfig: ComparatorHolder,
): List<SortItem> {
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

    val out = mutableListOf<SortItem>()
    out += mainItems
    if (subItems.isNotEmpty()) {
        out += SortItem.Section(
            id = "sub_items_section",
            text = TextHolder.Res(Res.string.options),
        )
        out += subItems
    }
    return out
}
