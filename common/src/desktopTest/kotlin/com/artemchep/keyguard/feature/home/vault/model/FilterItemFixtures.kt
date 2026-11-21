package com.artemchep.keyguard.feature.home.vault.model

import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel

// Filter panel items for the keyboard scene tests.

/** The chip titles of a [typeFilters] section, enough for a few lines. */
internal val TYPE_CHIPS = listOf("Login", "Card", "Identity", "Note", "SSH key", "Passkey")

/**
 * A flow section of the filter panel with a chip for each of the
 * [titles]. A chip is checked while its title is in [checked], its
 * click calls [onClick] with its title.
 */
internal fun typeFilters(
    titles: List<String> = TYPE_CHIPS,
    checked: Set<String> = emptySet(),
    onClick: (String) -> Unit = {},
): List<FilterItem> = listOf(FilterItem.Section(sectionId = "type", text = "Type", onClick = null)) +
    titles.map { title -> typeChip(title, checked = title in checked, onClick = { onClick(title) }) }

/** A chip of the type section of the filter panel, by default its click does nothing. */
internal fun typeChip(
    title: String,
    checked: Boolean = false,
    onClick: () -> Unit = {},
) = FilterItem.ChipItem(
    sectionId = "type",
    filterSectionId = "type",
    filter = FilterItem.Item.Filter.Toggle(filters = emptySet(), id = title),
    checked = checked,
    leading = null,
    title = title,
    text = null,
    onClick = onClick,
)

/** A list section of the filter panel with the [subfolder] nested in the expandable [folder]. */
internal fun folderFilters(folder: String, subfolder: String): List<FilterItem> = listOf(
    FilterItem.Section(
        sectionId = "folders",
        text = "Folders",
        layout = FilterItemModel.Section.Layout.List,
        onClick = null,
    ),
    folder(folder, parent = null),
    folder(subfolder, parent = folder),
)

private fun folder(title: String, parent: String?) = FilterItem.ListItem(
    sectionId = "folders",
    filterSectionId = "folders",
    filter = FilterItem.Item.Filter.Toggle(filters = emptySet(), id = title),
    nodeId = title,
    parentNodeId = parent,
    checked = false,
    depth = if (parent != null) 1 else 0,
    expandable = parent == null,
    leading = null,
    title = title,
    text = null,
    onClick = {},
)
