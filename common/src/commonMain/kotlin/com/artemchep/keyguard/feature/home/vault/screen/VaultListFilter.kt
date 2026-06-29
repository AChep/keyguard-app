package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import arrow.core.widen
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DTag
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.model.displayName
import com.artemchep.keyguard.common.model.iconImageVector
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.service.filter.AddCipherFilter
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.service.filter.model.AddCipherFilterRequest
import com.artemchep.keyguard.common.util.FolderHierarchyKey
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.common.util.createFolderHierarchyIndex
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.AccentColors
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardAuthReprompt
import com.artemchep.keyguard.ui.icons.KeyguardCipherFilter
import com.artemchep.keyguard.ui.icons.KeyguardFailedItems
import com.artemchep.keyguard.ui.icons.KeyguardIgnoredAlerts
import com.artemchep.keyguard.ui.icons.KeyguardPendingSyncItems
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.icons.iconSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.kodein.di.DirectDI
import org.kodein.di.instance

private fun <T, R> mapCiphers(
    flow: Flow<List<T>>,
    getter: (T) -> R,
) where R : Any? = flow
    .map { ciphers ->
        ciphers
            .asSequence()
            .map(getter)
            .toSet()
    }
    .distinctUntilChanged()

data class CreateFilterResult(
    val filterFlow: Flow<FilterHolder>,
    val onToggle: (String, Set<DFilter.Primitive>) -> Unit,
    val onApply: (Map<String, Set<DFilter.Primitive>>) -> Unit,
    val onClear: () -> Unit,
    val onSave: (Map<String, Set<DFilter.Primitive>>) -> Unit,
)

enum class FilterSection(
    val id: String,
    val title: TextHolder,
) {
    CUSTOM(
        id = "custom",
        title = TextHolder.Res(Res.string.custom),
    ),
    ACCOUNT(
        id = "account",
        title = TextHolder.Res(Res.string.account),
    ),
    ORGANIZATION(
        id = "organization",
        title = TextHolder.Res(Res.string.organization),
    ),
    TYPE(
        id = "type",
        title = TextHolder.Res(Res.string.type),
    ),
    TAG(
        id = "tag",
        title = TextHolder.Res(Res.string.tag),
    ),
    FOLDER(
        id = "folder",
        title = TextHolder.Res(Res.string.folder),
    ),
    COLLECTION(
        id = "collection",
        title = TextHolder.Res(Res.string.collection),
    ),
    MISC(
        id = "misc",
        title = TextHolder.Res(Res.string.misc),
    ),
}

enum class FilterZzz {

}

suspend fun RememberStateFlowScope.createFilter(
    directDI: DirectDI,
): CreateFilterResult {
    val addCipherFilter: AddCipherFilter = directDI.instance()
    val confirmationRouteFactory: ConfirmationRouteFactory = directDI.instance()

    val emptyState = FilterHolder(
        state = mapOf(),
    )

    val filterSink = mutablePersistedFlow<FilterHolder, String>(
        key = "ciphers.filters",
        serialize = { json, value ->
            json.encodeToString(value)
        },
        deserialize = { json, value ->
            json.decodeFromString(value)
        },
    ) { emptyState }
    val onClear = {
        filterSink.value = emptyState
    }
    val onSave = { state: Map<String, Set<DFilter.Primitive>> ->
        action {
            val intent = createConfirmationDialogIntent(
                confirmationRouteFactory = confirmationRouteFactory,
                item = ConfirmationRoute.Args.Item.StringItem(
                    key = "name",
                    title = translate(Res.string.generic_name),
                    canBeEmpty = false,
                ),
                icon = icon(Icons.Outlined.KeyguardCipherFilter, Icons.Outlined.Add),
                title = translate(Res.string.customfilters_add_filter_title),
            ) { name ->
                val request = AddCipherFilterRequest(
                    name = name,
                    filter = state,
                )
                addCipherFilter(request)
                    .launchIn(appScope)
            }
            navigate(intent)
        }
    }
    val onToggle = { sectionId: String, filters: Set<DFilter.Primitive> ->
        filterSink.update { holder ->
            val activeFilters = holder.state.getOrElse(sectionId) { emptySet() }
            val pendingFilters = filters
                .filter { it !in activeFilters }

            val newFilters = if (pendingFilters.isNotEmpty()) {
                // Add the filters from a clicked item if
                // not all of them are already active.
                activeFilters + pendingFilters
            } else {
                activeFilters - filters
            }
            val newState = holder.state + (sectionId to newFilters)
            holder.copy(
                state = newState,
            )
        }
    }
    val onApply = { state: Map<String, Set<DFilter.Primitive>> ->
        filterSink.update { holder ->
            if (holder.state == state) {
                // Reset the filters if you click on the
                // same item.
                return@update emptyState
            }

            holder.copy(
                state = state,
            )
        }
    }
    return CreateFilterResult(
        filterFlow = filterSink,
        onToggle = onToggle,
        onApply = onApply,
        onClear = onClear,
        onSave = onSave,
    )
}

data class OurFilterResult(
    val rev: Int = 0,
    val items: List<FilterItem> = emptyList(),
    val onClear: (() -> Unit)? = null,
    val onSave: (() -> Unit)? = null,
)

data class FilterParams(
    val deeplinkCustomFilterFlow: Flow<String>? = null,
    val section: Section = Section(),
) {
    data class Section(
        val account: Boolean = true,
        val type: Boolean = true,
        val organization: Boolean = true,
        val tag: Boolean = true,
        val collection: Boolean = true,
        val folder: Boolean = true,
        val misc: Boolean = true,
        val custom: Boolean = true,
    )
}

internal data class FolderFilterTreeItem(
    val path: List<String>,
    val title: String,
    val depth: Int,
    val folderId: String,
)

internal data class FolderFilterTreeNode(
    val path: List<String>,
    val title: String,
    val depth: Int,
    val folderIds: Set<String>,
    /**
     * True when any folder merged into this display path contains ciphers and
     * the node can be used as a folder filter target.
     */
    val selectable: Boolean,
    val expandable: Boolean,
) {
    val nodeId: String = folderFilterNodeId(path)

    val parentNodeId: String? = path
        .dropLast(1)
        .takeUnless { it.isEmpty() }
        ?.let(::folderFilterNodeId)
}

internal fun folderFilterNodeId(
    path: List<String>,
) = path.joinToString(separator = "|") { part ->
    "${part.length}:$part"
}

internal data class FolderFilterTree(
    val nodes: List<FolderFilterTreeNode>,
    val useNestedUi: Boolean,
)

/**
 * Builds the folder filter tree from a flat list of [folders].
 *
 * Folder hierarchy is resolved per account, but nodes that resolve to the same
 * display name-path are then merged ACROSS accounts into a single filter node
 * (so "Personal" in two accounts is one entry). A node is [selectable] when any
 * of its merged folders holds ciphers ([folderIdsWithCiphers]); a node is kept
 * when it is selectable or has a selectable descendant.
 */
internal fun buildFolderFilterTree(
    folders: List<DFolder>,
    folderIdsWithCiphers: Set<String?>,
): FolderFilterTree {
    // One per-account hierarchy index for the whole folder set.
    val index = createFolderHierarchyIndex(
        folders = folders,
        accountId = { it.accountId },
        lens = { it.name },
        id = { it.id },
        parentId = { it.parentId },
        hierarchyMode = { it.hierarchyMode },
    )

    // The display name-path (root..leaf) of a folder, read off the index by
    // walking its parent chain. The chain is acyclic (the index re-roots
    // cycles), so the visited guard is only a safety net.
    fun pathOf(folder: DFolder): List<String> {
        val key = when (folder.hierarchyMode) {
            FolderHierarchyMode.Path -> FolderHierarchyKey.Path(
                accountId = folder.accountId,
                path = folder.name,
            )

            FolderHierarchyMode.ParentId -> FolderHierarchyKey.Id(
                accountId = folder.accountId,
                folderId = folder.id,
            )
        }
        val names = ArrayDeque<String>()
        val visited = mutableSetOf<FolderHierarchyKey>()
        var current = index.node(key)
        while (current != null && visited.add(current.key)) {
            names.addFirst(current.name)
            val parentKey = current.parentKey
                ?: break
            current = index.node(parentKey)
        }
        return names
            .toList()
            .takeUnless { it.isEmpty() }
            ?: listOf(folder.name)
    }

    val folderItems = folders
        .asSequence()
        .map { folder ->
            val path = pathOf(folder)
            FolderFilterTreeItem(
                path = path,
                title = path.last(),
                depth = (path.size - 1).coerceAtLeast(0),
                folderId = folder.id,
            )
        }
        .groupBy { it.path }
        .asSequence()
        .sortedWith(
            StringComparatorIgnoreCase { entry ->
                entry.key.joinToString(separator = "/")
            },
        )
        .map { entry ->
            val firstItem = entry.value.first()
            val folderIds = entry.value
                .asSequence()
                .map { it.folderId }
                .toSet()
            val selectable = folderIds.any { folderId ->
                folderId in folderIdsWithCiphers
            }
            firstItem.path to FolderFilterTreeNode(
                path = firstItem.path,
                title = firstItem.title,
                depth = firstItem.depth,
                folderIds = folderIds,
                selectable = selectable,
                expandable = false,
            )
        }
        .toList()
    val selectablePaths = folderItems
        .asSequence()
        .filter { (_, node) ->
            node.selectable
        }
        .map { (path, _) ->
            path
        }
        .toList()

    fun List<String>.hasSelectableDescendant(): Boolean = selectablePaths
        .any { selectablePath ->
            selectablePath.size > size &&
                    selectablePath.take(size) == this
        }

    val folderNodes = folderItems
        .asSequence()
        .map { (path, node) ->
            node.copy(
                expandable = path.hasSelectableDescendant(),
            )
        }
        .filter { node ->
            node.selectable || node.expandable
        }
        .toList()
    val useNestedUi = folderNodes
        .any { node ->
            node.selectable && node.depth > 0
        }
    return FolderFilterTree(
        nodes = folderNodes,
        useNestedUi = useNestedUi,
    )
}

suspend fun <
        Output : Any,
        Account,
        Secret,
        Tag,
        Folder,
        Collection,
        Organization,
        > RememberStateFlowScope.ah(
    directDI: DirectDI,
    outputGetter: (Output) -> DSecret,
    outputFlow: Flow<List<Output>>,
    accountGetter: (Account) -> DAccount,
    accountFlow: Flow<List<Account>>,
    profileFlow: Flow<List<DProfile>>,
    cipherGetter: (Secret) -> DSecret,
    cipherFlow: Flow<List<Secret>>,
    tagGetter: (Tag) -> DTag,
    tagFlow: Flow<List<Tag>>,
    folderGetter: (Folder) -> DFolder,
    folderFlow: Flow<List<Folder>>,
    collectionGetter: (Collection) -> DCollection,
    collectionFlow: Flow<List<Collection>>,
    organizationGetter: (Organization) -> DOrganization,
    organizationFlow: Flow<List<Organization>>,
    input: CreateFilterResult,
    params: FilterParams = FilterParams(),
): Flow<OurFilterResult> {
    val getCipherFilters: GetCipherFilters = directDI.instance()

    val storage = kotlin.run {
        val disk = loadDiskHandle("ciphers.filter")
        PersistedStorage.InDisk(disk)
    }

    val collapsedSectionIdsSink =
        mutablePersistedFlow<List<String>>("ciphers.sections", storage) { emptyList() }

    fun toggleSection(sectionId: String) {
        collapsedSectionIdsSink.update {
            val shouldAdd = sectionId !in it
            if (shouldAdd) {
                it + sectionId
            } else {
                it - sectionId
            }
        }
    }

    val outputCipherFlow = outputFlow
        .map { list ->
            list
                .map { outputGetter(it) }
        }

    val filterTypesWithCiphers = mapCiphers(cipherFlow) { cipherGetter(it).type }
    val filterFoldersWithCiphers = mapCiphers(cipherFlow) { cipherGetter(it).folderId }
    val filterAccountsWithCiphers = mapCiphers(cipherFlow) { cipherGetter(it).accountId }
    val filterOrganizationsWithCiphers = mapCiphers(cipherFlow) { cipherGetter(it).organizationId }
    val filterTagsWithCiphers = cipherFlow
        .map { ciphers ->
            ciphers
                .asSequence()
                .flatMap {
                    val cipher = cipherGetter(it)
                    cipher.tags
                        .takeUnless { it.isEmpty() }
                        ?: listOf(null)
                }
                .toSet()
        }
        .distinctUntilChanged()
    val filterCollectionsWithCiphers = cipherFlow
        .map { ciphers ->
            ciphers
                .asSequence()
                .flatMap {
                    val cipher = cipherGetter(it)
                    cipher.collectionIds
                        .takeUnless { it.isEmpty() }
                        ?: listOf(null)
                }
                .toSet()
        }
        .distinctUntilChanged()

    fun Flow<List<FilterItem>>.filterSection(
        enabled: Boolean,
    ) = if (enabled) {
        this
    } else {
        flowOf(emptyList())
    }

    fun getFilterSectionId(item: FilterItem): String? = when (item) {
        is FilterItem.ChipItem -> item.filterSectionId
        is FilterItem.ListItem -> item.filterSectionId
        is FilterItem.Section -> null
    }

    fun getFilter(item: FilterItem): FilterItem.Item.Filter? = when (item) {
        is FilterItem.ChipItem -> item.filter
        is FilterItem.ListItem -> item.filter
        is FilterItem.Section -> null
    }

    fun getChecked(item: FilterItem): Boolean = when (item) {
        is FilterItem.ChipItem -> item.checked
        is FilterItem.ListItem -> item.checked
        is FilterItem.Section -> false
    }

    fun FilterItem.Item.withChecked(checked: Boolean): FilterItem.Item = when (this) {
        is FilterItem.ChipItem -> copy(checked = checked)
        is FilterItem.ListItem -> copy(checked = checked)
    }

    fun FilterItem.withEnabled(enabled: Boolean): FilterItem = when (this) {
        is FilterItem.ChipItem -> if (enabled) {
            this
        } else {
            copy(
                onClick = null,
                enabled = false,
            )
        }

        is FilterItem.ListItem -> if (enabled) {
            this
        } else if (expandable) {
            copy(
                onClick = null,
                enabled = true,
            )
        } else {
            copy(
                onClick = null,
                enabled = false,
            )
        }

        is FilterItem.Section -> this
    }

    fun Flow<List<FilterItem.Item>>.aaa(
        sectionId: String,
        sectionTitle: String,
        collapse: Boolean = true,
        layout: (List<FilterItem.Item>) -> FilterItemModel.Section.Layout = {
            FilterItemModel.Section.Layout.Flow
        },
        checked: (FilterItem.Item, FilterHolder) -> Boolean,
    ) = this
        .combine(input.filterFlow) { items, filterHolder ->
            items
                .map { item ->
                    val shouldBeChecked = checked(item, filterHolder)
                    if (shouldBeChecked == item.checked) {
                        return@map item
                    }

                    item.withChecked(shouldBeChecked)
                }
        }
        .distinctUntilChanged()
        .map { items ->
            if (items.size <= 1 && collapse || items.isEmpty()) {
                // Do not show a single filter item.
                return@map emptyList<FilterItem>()
            }

            items
                .widen<FilterItem, FilterItem.Item>()
                .toMutableList()
                .apply {
                    val sectionItem = FilterItem.Section(
                        sectionId = sectionId,
                        text = sectionTitle,
                        layout = layout(items),
                        onClick = {
                            toggleSection(sectionId)
                        },
                    )
                    add(0, sectionItem)
                }
        }

    fun Flow<List<FilterItem.Item>>.aaa(
        sectionId: String,
        sectionTitle: String,
        collapse: Boolean = true,
        layout: (List<FilterItem.Item>) -> FilterItemModel.Section.Layout = {
            FilterItemModel.Section.Layout.Flow
        },
    ) = aaa(
        sectionId = sectionId,
        sectionTitle = sectionTitle,
        collapse = collapse,
        layout = layout,
        checked = { item, filterHolder ->
            val filter = item.filter
                ?: return@aaa false

            val filterSectionId = item.filterSectionId
            when (filter) {
                is FilterItem.Item.Filter.Toggle -> {
                    val activeFilters = filterHolder.state[filterSectionId].orEmpty()
                    filter.filters
                        .all { itemFilter ->
                            itemFilter in activeFilters
                        }
                }

                is FilterItem.Item.Filter.Apply -> kotlin.run {
                    // If the size of the current state and the item
                    // state is different then there's no way it's currently
                    // selected.
                    if (filterHolder.state.size != filter.filters.size) {
                        return@run false
                    }

                    filter.filters
                        .all { (filterSectionId, filterSet) ->
                            val activeFilters = filterHolder.state[filterSectionId].orEmpty()
                            activeFilters == filterSet
                        }
                }
            }
        },
    )

    fun createFolderFilter(
        folderIds: Set<String?>,
    ) = folderIds
        .asSequence()
        .map { folderId ->
            DFilter.ById(
                id = folderId,
                what = DFilter.ById.What.FOLDER,
            )
        }
        .toSet()

    val setOfNull = setOf(null)

    fun createFilterAction(
        sectionId: String,
        filter: Set<DFilter.Primitive>,
        filterSectionId: String = sectionId,
        title: String,
        text: String? = null,
        textMaxLines: Int? = null,
        tint: AccentColors? = null,
        icon: ImageVector? = null,
    ) = kotlin.run {
        val leading: (@Composable () -> Unit)? = when {
            icon != null -> {
                // composable
                {
                    IconBox(main = icon)
                }
            }

            tint != null -> {
                // composable
                {
                    Box(
                        modifier = Modifier
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val color = rememberSecretAccentColor(
                            accentLight = tint.light,
                            accentDark = tint.dark,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(color, CircleShape),
                        )
                    }
                }
            }

            else -> null
        }

        val onClick = input.onToggle
            .partially1(filterSectionId)
            .partially1(filter)
        FilterItem.ChipItem(
            sectionId = sectionId,
            filterSectionId = filterSectionId,
            filter = FilterItem.Item.Filter.Toggle(
                filters = filter,
            ),
            leading = leading,
            title = title,
            text = text,
            textMaxLines = textMaxLines,
            onClick = onClick,
            checked = false,
        )
    }

    fun createListFilterAction(
        sectionId: String,
        filter: Set<DFilter.Primitive>,
        filterSectionId: String = sectionId,
        title: String,
        text: String? = null,
        textMaxLines: Int? = null,
        tint: AccentColors? = null,
        icon: ImageVector? = null,
    ): FilterItem.ListItem {
        val leading: (@Composable () -> Unit)? = when {
            icon != null -> {
                // composable
                {
                    IconBox(main = icon)
                }
            }

            tint != null -> {
                // composable
                {
                    Box(
                        modifier = Modifier
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val color = rememberSecretAccentColor(
                            accentLight = tint.light,
                            accentDark = tint.dark,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(color, CircleShape),
                        )
                    }
                }
            }

            else -> null
        }

        val onClick = input.onToggle
            .partially1(filterSectionId)
            .partially1(filter)
        return FilterItem.ListItem(
            sectionId = sectionId,
            filterSectionId = filterSectionId,
            filter = FilterItem.Item.Filter.Toggle(
                filters = filter,
            ),
            leading = leading,
            title = title,
            text = text,
            textMaxLines = textMaxLines,
            onClick = onClick,
            checked = false,
            depth = 0,
            nodeId = "",
            parentNodeId = null,
            expandable = false,
        )
    }

    fun createAccountFilterAction(
        accountIds: Set<String?>,
        title: String,
        text: String,
        tint: AccentColors? = null,
        icon: ImageVector? = null,
    ) = createFilterAction(
        sectionId = FilterSection.ACCOUNT.id,
        filter = accountIds
            .asSequence()
            .map { accountId ->
                DFilter.ById(
                    id = accountId,
                    what = DFilter.ById.What.ACCOUNT,
                )
            }
            .toSet(),
        title = title,
        text = text,
        textMaxLines = 1,
        tint = tint,
        icon = icon,
    )

    suspend fun createTypeFilterAction(
        type: DSecret.Type,
        sectionId: String = FilterSection.TYPE.id,
    ) = createFilterAction(
        sectionId = sectionId,
        filterSectionId = FilterSection.TYPE.id,
        filter = setOf(
            DFilter.ByType(type),
        ),
        title = translate(type.titleH()),
        icon = type.iconImageVector(),
    )

    fun createFolderFilterAction(
        folderIds: Set<String?>,
        title: String,
        icon: ImageVector? = null,
    ) = createFilterAction(
        sectionId = FilterSection.FOLDER.id,
        filter = createFolderFilter(folderIds),
        title = title,
        icon = icon,
    )

    fun createListFilterAction(
        folderIds: Set<String>,
        title: String,
        nodeId: String,
        parentNodeId: String?,
        expandable: Boolean,
        depth: Int,
        selectable: Boolean,
    ): FilterItem.ListItem {
        val filter = if (selectable) {
            FilterItem.Item.Filter.Toggle(
                filters = createFolderFilter(folderIds),
            )
        } else {
            null
        }

        return FilterItem.ListItem(
            sectionId = FilterSection.FOLDER.id,
            filterSectionId = FilterSection.FOLDER.id,
            filter = filter,
            nodeId = nodeId,
            parentNodeId = parentNodeId,
            leading = icon(Icons.Outlined.Folder),
            title = title,
            text = null,
            onClick = filter
                ?.filters
                ?.let { filters ->
                    input.onToggle
                        .partially1(FilterSection.FOLDER.id)
                        .partially1(filters)
                },
            expandable = expandable,
            enabled = selectable || expandable,
            depth = depth,
            checked = false,
        )
    }

    fun createTagFilterAction(
        tags: Set<String?>,
        title: String,
        icon: ImageVector? = null,
    ) = createFilterAction(
        sectionId = FilterSection.TAG.id,
        filter = tags
            .asSequence()
            .map { tag ->
                DFilter.ById(
                    id = tag,
                    what = DFilter.ById.What.TAG,
                )
            }
            .toSet(),
        title = title,
        icon = icon,
    )

    fun createCollectionFilterAction(
        collectionIds: Set<String?>,
        title: String,
        icon: ImageVector? = null,
    ) = createFilterAction(
        sectionId = FilterSection.COLLECTION.id,
        filter = collectionIds
            .asSequence()
            .map { collectionId ->
                DFilter.ById(
                    id = collectionId,
                    what = DFilter.ById.What.COLLECTION,
                )
            }
            .toSet(),
        title = title,
        icon = icon,
    )

    fun createOrganizationFilterAction(
        organizationIds: Set<String?>,
        title: String,
        icon: ImageVector? = null,
    ) = createFilterAction(
        sectionId = FilterSection.ORGANIZATION.id,
        filter = organizationIds
            .asSequence()
            .map { organizationId ->
                DFilter.ById(
                    id = organizationId,
                    what = DFilter.ById.What.ORGANIZATION,
                )
            }
            .toSet(),
        title = title,
        icon = icon,
    )

    val filterAccountListFlow = profileFlow
        .map { profiles ->
            profiles
                .map { profile ->
                    createAccountFilterAction(
                        accountIds = setOf(
                            profile.accountId,
                        ),
                        title = profile.displayName,
                        text = profile.accountHost,
                        tint = profile.accentColor,
                    )
                }
        }
        .combine(filterAccountsWithCiphers) { items, accountIds ->
            items
                .filter { filterItem ->
                    val filterItemFilter = filterItem.filter as FilterItem.Item.Filter.Toggle
                    filterItemFilter.filters
                        .any { filter ->
                            val filterFixed = filter as DFilter.ById
                            require(filterFixed.what == DFilter.ById.What.ACCOUNT)
                            filterFixed.id in accountIds
                        }
                }
        }
        .aaa(
            sectionId = FilterSection.ACCOUNT.id,
            sectionTitle = translate(FilterSection.ACCOUNT.title),
        )
        .filterSection(params.section.account)

    val filterTypesAll = listOf(
        DSecret.Type.Login to createTypeFilterAction(
            type = DSecret.Type.Login,
        ),
        DSecret.Type.Card to createTypeFilterAction(
            type = DSecret.Type.Card,
        ),
        DSecret.Type.Identity to createTypeFilterAction(
            type = DSecret.Type.Identity,
        ),
        DSecret.Type.SecureNote to createTypeFilterAction(
            type = DSecret.Type.SecureNote,
        ),
        DSecret.Type.SshKey to createTypeFilterAction(
            type = DSecret.Type.SshKey,
        ),
    )

    val filterTypeListFlow = filterTypesWithCiphers
        .map { types ->
            filterTypesAll.mapNotNull { (type, item) ->
                item.takeIf { type in types }
            }
        }
        .aaa(
            sectionId = FilterSection.TYPE.id,
            sectionTitle = translate(FilterSection.TYPE.title),
        )
        .filterSection(params.section.type)

    val filterFolderListFlow = folderFlow
        .combine(filterFoldersWithCiphers) { folders, folderIdsWithCiphers ->
            val folderModels = folders
                .asSequence()
                .map(folderGetter)
                .filterNot { it.deleted }
                .toList()
            val folderFilterTree = buildFolderFilterTree(
                folders = folderModels,
                folderIdsWithCiphers = folderIdsWithCiphers,
            )
            val folderNodes = folderFilterTree.nodes
            val useNestedUi = folderFilterTree.useNestedUi

            val folderFilterItems = if (useNestedUi) {
                folderNodes
                    .map { node ->
                        createListFilterAction(
                            folderIds = node.folderIds,
                            title = node.title,
                            nodeId = node.nodeId,
                            parentNodeId = node.parentNodeId,
                            expandable = node.expandable,
                            depth = node.depth,
                            selectable = node.selectable,
                        )
                    }
            } else {
                folderNodes
                    .asSequence()
                    .filter { node -> node.selectable }
                    .map { node ->
                        createFolderFilterAction(
                            folderIds = node.folderIds,
                            title = node.title,
                        )
                    }
                    .toList()
            }
            val folderItem = if (useNestedUi) {
                val sectionId = FilterSection.FOLDER.id
                val filter = createFolderFilter(setOfNull)
                createListFilterAction(
                    sectionId = sectionId,
                    filter = filter,
                    filterSectionId = sectionId,
                    title = translate(Res.string.folder_none),
                    icon = Icons.Outlined.FolderOff,
                )
            } else {
                createFolderFilterAction(
                    folderIds = setOfNull,
                    title = translate(Res.string.folder_none),
                    icon = Icons.Outlined.FolderOff,
                )
            }

            (folderFilterItems + folderItem)
                .let { items ->
                    items
                        .filter { filterItem ->
                            val filterItemFilter =
                                getFilter(filterItem) as? FilterItem.Item.Filter.Toggle
                                    ?: return@filter true
                            filterItemFilter.filters
                                .any { filter ->
                                    val filterFixed = filter as DFilter.ById
                                    require(filterFixed.what == DFilter.ById.What.FOLDER)
                                    filterFixed.id in folderIdsWithCiphers
                                }
                        }
                }
        }
        .aaa(
            sectionId = FilterSection.FOLDER.id,
            sectionTitle = translate(FilterSection.FOLDER.title),
            layout = { items ->
                val hasListItems = items
                    .any { it is FilterItem.ListItem }
                if (hasListItems) {
                    FilterItemModel.Section.Layout.List
                } else FilterItemModel.Section.Layout.Flow
            },
        )
        .filterSection(params.section.folder)

    val filterTagListFlow = tagFlow
        .map { tags ->
            tags
                .groupBy { tag ->
                    val model = tagGetter(tag)
                    model.name
                }
                .asSequence()
                .map { (name, tags) ->
                    createTagFilterAction(
                        tags = tags
                            .asSequence()
                            .map(tagGetter)
                            .map { it.name }
                            .toSet(),
                        title = name,
                    )
                }
                .sortedWith(StringComparatorIgnoreCase { it.title })
                .toList() +
                    createTagFilterAction(
                        tags = setOfNull,
                        title = translate(Res.string.tag_none),
                    )
        }
        .combine(filterTagsWithCiphers) { items, tags ->
            items
                .filter { filterItem ->
                    val filterItemFilter = filterItem.filter as FilterItem.Item.Filter.Toggle
                    filterItemFilter.filters
                        .any { filter ->
                            val filterFixed = filter as DFilter.ById
                            require(filterFixed.what == DFilter.ById.What.TAG)
                            filterFixed.id in tags
                        }
                }
        }
        .aaa(
            sectionId = FilterSection.TAG.id,
            sectionTitle = translate(FilterSection.TAG.title),
        )
        .filterSection(params.section.tag)

    val filterCollectionListFlow = collectionFlow
        .map { collections ->
            collections
                .groupBy { collection ->
                    val model = collectionGetter(collection)
                    model.name
                }
                .asSequence()
                .map { (name, collections) ->
                    createCollectionFilterAction(
                        collectionIds = collections
                            .asSequence()
                            .map(collectionGetter)
                            .map { it.id }
                            .toSet(),
                        title = name,
                    )
                }
                .sortedWith(StringComparatorIgnoreCase { it.title })
                .toList() +
                    createCollectionFilterAction(
                        collectionIds = setOfNull,
                        title = translate(Res.string.collection_none),
                    )
        }
        .combine(filterCollectionsWithCiphers) { items, collectionIds ->
            items
                .filter { filterItem ->
                    val filterItemFilter = filterItem.filter as FilterItem.Item.Filter.Toggle
                    filterItemFilter.filters
                        .any { filter ->
                            val filterFixed = filter as DFilter.ById
                            require(filterFixed.what == DFilter.ById.What.COLLECTION)
                            filterFixed.id in collectionIds
                        }
                }
        }
        .aaa(
            sectionId = FilterSection.COLLECTION.id,
            sectionTitle = translate(FilterSection.COLLECTION.title),
        )
        .filterSection(params.section.collection)

    val filterOrganizationListFlow = organizationFlow
        .map { organizations ->
            organizations
                .groupBy { organization ->
                    val model = organizationGetter(organization)
                    model.name
                }
                .asSequence()
                .map { (name, organizations) ->
                    createOrganizationFilterAction(
                        organizationIds = organizations
                            .asSequence()
                            .map(organizationGetter)
                            .map { it.id }
                            .toSet(),
                        title = name,
                    )
                }
                .sortedWith(StringComparatorIgnoreCase { it.title })
                .toList() +
                    createOrganizationFilterAction(
                        organizationIds = setOfNull,
                        title = translate(Res.string.organization_none),
                    )
        }
        .combine(filterOrganizationsWithCiphers) { items, organizationIds ->
            items
                .filter { filterItem ->
                    val filterItemFilter = filterItem.filter as FilterItem.Item.Filter.Toggle
                    filterItemFilter.filters
                        .any { filter ->
                            val filterFixed = filter as DFilter.ById
                            require(filterFixed.what == DFilter.ById.What.ORGANIZATION)
                            filterFixed.id in organizationIds
                        }
                }
        }
        .aaa(
            sectionId = FilterSection.ORGANIZATION.id,
            sectionTitle = translate(FilterSection.ORGANIZATION.title),
        )
        .filterSection(params.section.organization)

    val filterMiscAll = listOf(
        createListFilterAction(
            sectionId = FilterSection.MISC.id,
            filter = setOf(
                DFilter.ByOtp,
            ),
            filterSectionId = "${FilterSection.MISC.id}.otp",
            title = translate(DFilter.ByOtp.content.title),
            icon = DFilter.ByOtp.content.icon,
        ),
        createListFilterAction(
            sectionId = FilterSection.MISC.id,
            filter = setOf(
                DFilter.ByAttachments,
            ),
            filterSectionId = "${FilterSection.MISC.id}.attachments",
            title = translate(DFilter.ByAttachments.content.title),
            icon = DFilter.ByAttachments.content.icon,
        ),
        createListFilterAction(
            sectionId = FilterSection.MISC.id,
            filter = setOf(
                DFilter.ByPasskeys,
            ),
            filterSectionId = "${FilterSection.MISC.id}.passkeys",
            title = translate(DFilter.ByPasskeys.content.title),
            icon = DFilter.ByPasskeys.content.icon,
        ),
        createListFilterAction(
            sectionId = FilterSection.MISC.id,
            filter = setOf(
                DFilter.ByReprompt(reprompt = true),
            ),
            filterSectionId = "${FilterSection.MISC.id}.reprompt",
            title = translate(Res.string.filter_auth_reprompt_items),
            icon = Icons.Outlined.KeyguardAuthReprompt,
        ),
        createListFilterAction(
            sectionId = FilterSection.MISC.id,
            filter = setOf(
                DFilter.BySync(synced = false),
            ),
            filterSectionId = "${FilterSection.MISC.id}.sync",
            title = translate(Res.string.filter_pending_items),
            icon = Icons.Outlined.KeyguardPendingSyncItems,
        ),
        createListFilterAction(
            sectionId = FilterSection.MISC.id,
            filter = setOf(
                DFilter.ByError(error = true),
            ),
            filterSectionId = "${FilterSection.MISC.id}.error",
            title = translate(Res.string.filter_failed_items),
            icon = Icons.Outlined.KeyguardFailedItems,
        ),
        createListFilterAction(
            sectionId = FilterSection.MISC.id,
            filter = setOf(
                DFilter.ByIgnoredAlerts,
            ),
            filterSectionId = "${FilterSection.MISC.id}.watchtower_alerts",
            title = translate(Res.string.ignored_alerts),
            icon = Icons.Outlined.KeyguardIgnoredAlerts,
        ),
    )

    val filterMiscListFlow = flowOf(Unit)
        .map {
            filterMiscAll
        }
        .aaa(
            sectionId = FilterSection.MISC.id,
            sectionTitle = translate(FilterSection.MISC.title),
            collapse = false,
            layout = {
                FilterItemModel.Section.Layout.List
            },
        )
        .filterSection(params.section.misc)

    // Auto-apply deep link filters
    params.deeplinkCustomFilterFlow
        ?.onEach { customFilterId ->
            val customFilter = kotlin.run {
                val customFilters = getCipherFilters()
                    .first()
                customFilters.firstOrNull { it.id == customFilterId }
            }
            if (customFilter != null) {
                input.onApply(customFilter.filter)
            }
        }
        ?.launchIn(screenScope)

    val filterCustomListFlow = getCipherFilters()
        .map { filters ->
            filters
                .map { filter ->
                    FilterItem.ChipItem(
                        sectionId = FilterSection.CUSTOM.id,
                        filterSectionId = FilterSection.CUSTOM.id,
                        filter = FilterItem.Item.Filter.Apply(
                            filters = filter.filter,
                            id = filter.id,
                        ),
                        leading = filter.icon
                            ?.let {
                                iconSmall(it)
                            },
                        title = filter.name,
                        text = null,
                        onClick = input.onApply
                            .partially1(filter.filter),
                        checked = false,
                    )
                }
        }
        .aaa(
            sectionId = FilterSection.CUSTOM.id,
            sectionTitle = translate(FilterSection.CUSTOM.title),
            collapse = false,
        )
        .filterSection(params.section.custom)

    return combine(
        filterCustomListFlow,
        filterAccountListFlow,
        filterOrganizationListFlow,
        filterTypeListFlow,
        filterTagListFlow,
        filterFolderListFlow,
        filterCollectionListFlow,
        filterMiscListFlow,
    ) { a -> a.flatMap { it } }
        .combine(collapsedSectionIdsSink) { items, collapsedSectionIds ->
            var skippedSectionId: String? = null

            val out = mutableListOf<FilterItem>()
            items.forEach { item ->
                if (item is FilterItem.Section) {
                    val collapsed = item.sectionId in collapsedSectionIds
                    skippedSectionId = item.sectionId.takeIf { collapsed }

                    out += if (collapsed) {
                        item.copy(expanded = false)
                    } else {
                        item
                    }
                } else {
                    if (item.sectionId != skippedSectionId) {
                        out += item
                    }
                }
            }
            out
        }
        .combine(outputCipherFlow) { items, outputCiphers ->
            val checkedSectionIds = items
                .asSequence()
                .mapNotNull { item ->
                    val checked = getChecked(item)
                    if (checked) {
                        getFilterSectionId(item)
                    } else {
                        null
                    }
                }
                .toSet()

            val out = mutableListOf<FilterItem>()
            items.forEach { item ->
                when (item) {
                    is FilterItem.Section -> out += item
                    else -> {
                        val filterSectionId = getFilterSectionId(item)
                        val fastEnabled = getChecked(item) ||
                                // If one of the items in a section is enabled, then
                                // enable the whole section.
                                filterSectionId in checkedSectionIds
                        val enabled = fastEnabled || kotlin.run {
                            val filterItemFilter = getFilter(item) as? FilterItem.Item.Filter.Toggle
                                ?: return@run true
                            filterItemFilter.filters
                                .any { filter ->
                                    val filterPredicate = filter.prepare(directDI, outputCiphers)
                                    outputCiphers.any(filterPredicate)
                                }
                        }

                        out += item.withEnabled(enabled)
                    }
                }
            }
            out
        }
        .combine(input.filterFlow) { a, b ->
            OurFilterResult(
                rev = b.id,
                items = a,
                onClear = input.onClear.takeIf { b.id != 0 },
                onSave = input.onSave
                    .takeIf { b.id != 0 }
                    ?.partially1(b.state),
            )
        }
        .flowOn(Dispatchers.Default)
}
