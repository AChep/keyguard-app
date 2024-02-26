package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import arrow.core.widen
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.iconImageVector
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.usecase.GetFolderTree
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.AccentColors
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardAttachment
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
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
    val onClear: () -> Unit,
)

suspend fun RememberStateFlowScope.createFilter(): CreateFilterResult {
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
    return CreateFilterResult(
        filterFlow = filterSink,
        onToggle = onToggle,
        onClear = onClear,
    )
}

data class OurFilterResult(
    val rev: Int = 0,
    val items: List<FilterItem> = emptyList(),
    val onClear: (() -> Unit)? = null,
)

data class FilterParams(
    val deeplinkCustomFilter: String? = null,
    val section: Section = Section(),
) {
    data class Section(
        val account: Boolean = true,
        val type: Boolean = true,
        val organization: Boolean = true,
        val collection: Boolean = true,
        val folder: Boolean = true,
        val misc: Boolean = true,
        val custom: Boolean = true,
    )
}

suspend fun <
        Output : Any,
        Account,
        Secret,
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
    folderGetter: (Folder) -> DFolder,
    folderFlow: Flow<List<Folder>>,
    collectionGetter: (Collection) -> DCollection,
    collectionFlow: Flow<List<Collection>>,
    organizationGetter: (Organization) -> DOrganization,
    organizationFlow: Flow<List<Organization>>,
    input: CreateFilterResult,
    params: FilterParams = FilterParams(),
): Flow<OurFilterResult> {
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

    fun Flow<List<FilterItem.Item>>.aaa(
        sectionId: String,
        sectionTitle: String,
        collapse: Boolean = true,
    ) = this
        .combine(input.filterFlow) { items, filterHolder ->
            items
                .map { item ->
                    val shouldBeChecked = kotlin.run {
                        val activeFilters = filterHolder.state[item.filterSectionId].orEmpty()
                        item.filters
                            .all { itemFilter ->
                                itemFilter in activeFilters
                            }
                    }
                    if (shouldBeChecked == item.checked) {
                        return@map item
                    }

                    item.copy(checked = shouldBeChecked)
                }
        }
        .distinctUntilChanged()
        .map { items ->
            if (items.size <= 1 && collapse) {
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
                        onClick = {
                            toggleSection(sectionId)
                        },
                    )
                    add(0, sectionItem)
                }
        }

    val setOfNull = setOf(null)

    val customSectionId = "custom"
    val typeSectionId = "type"
    val accountSectionId = "account"
    val folderSectionId = "folder"
    val collectionSectionId = "collection"
    val organizationSectionId = "organization"
    val miscSectionId = "misc"

    fun createFilterAction(
        sectionId: String,
        filter: Set<DFilter.Primitive>,
        filterSectionId: String = sectionId,
        title: String,
        text: String? = null,
        tint: AccentColors? = null,
        icon: ImageVector? = null,
        fill: Boolean = false,
        indent: Int = 0,
    ) = FilterItem.Item(
        sectionId = sectionId,
        filterSectionId = filterSectionId,
        filters = filter,
        leading = when {
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
        },
        title = title,
        text = text,
        onClick = input.onToggle
            .partially1(filterSectionId)
            .partially1(filter),
        fill = fill,
        indent = indent,
        checked = false,
    )

    fun createAccountFilterAction(
        accountIds: Set<String?>,
        title: String,
        text: String,
        tint: AccentColors? = null,
        icon: ImageVector? = null,
    ) = createFilterAction(
        sectionId = accountSectionId,
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
        tint = tint,
        icon = icon,
    )

    fun createTypeFilterAction(
        type: DSecret.Type,
        sectionId: String = typeSectionId,
    ) = createFilterAction(
        sectionId = sectionId,
        filterSectionId = typeSectionId,
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
        fill: Boolean,
        indent: Int,
    ) = createFilterAction(
        sectionId = folderSectionId,
        filter = folderIds
            .asSequence()
            .map { folderId ->
                DFilter.ById(
                    id = folderId,
                    what = DFilter.ById.What.FOLDER,
                )
            }
            .toSet(),
        title = title,
        icon = icon,
        fill = fill,
        indent = indent,
    )

    fun createCollectionFilterAction(
        collectionIds: Set<String?>,
        title: String,
        icon: ImageVector? = null,
    ) = createFilterAction(
        sectionId = collectionSectionId,
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
        sectionId = organizationSectionId,
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
                        title = profile.email,
                        text = profile.accountHost,
                        tint = profile.accentColor,
                    )
                }
        }
        .combine(filterAccountsWithCiphers) { items, accountIds ->
            items
                .filter { filterItem ->
                    filterItem.filters
                        .any { filter ->
                            val filterFixed = filter as DFilter.ById
                            require(filterFixed.what == DFilter.ById.What.ACCOUNT)
                            filterFixed.id in accountIds
                        }
                }
        }
        .aaa(
            sectionId = accountSectionId,
            sectionTitle = translate(Res.strings.account),
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
    )

    val filterTypeListFlow = filterTypesWithCiphers
        .map { types ->
            filterTypesAll.mapNotNull { (type, item) ->
                item.takeIf { type in types }
            }
        }
        .aaa(
            sectionId = typeSectionId,
            sectionTitle = translate(Res.strings.type),
        )
        .filterSection(params.section.type)

    val folderTree: GetFolderTree = directDI.instance()
    val filterFolderListFlow = folderFlow
        .map { folders ->
            val q = folders
                .filter { folder ->
                    val model = folderGetter(folder)
                    !model.deleted
                }
                .groupBy { folder ->
                    val model = folderGetter(folder)
                    model.name
                }
            val w = q
                .map {
                    folderTree.invoke(
                        lens = { it.key },
                        allFolders = q.entries,
                        folder = it,
                    )
                }
            val p = w
                .asSequence()
                .flatMap {
                    it.hierarchy
                        .dropLast(1)
                }
                .map { it.folder.key }
                .toSet()

            w
                .asSequence()
                .sortedBy { it.folder.key }
                .map { entry ->
                    val name = entry.folder.key // entry.hierarchy.last().name
                    val folders = entry.folder.value
                    createFolderFilterAction(
                        folderIds = folders
                            .asSequence()
                            .map(folderGetter)
                            .map { it.id }
                            .toSet(),
                        title = name,
                        fill = false,// entry.folder.key in p || entry.hierarchy.size > 1,
                        indent = 0, // entry.hierarchy.lastIndex,
                    )
                }
                .toList() +
                    createFolderFilterAction(
                        folderIds = setOfNull,
                        title = translate(Res.strings.folder_none),
                        icon = Icons.Outlined.FolderOff,
                        fill = false,
                        indent = 0,
                    )
        }
        .combine(filterFoldersWithCiphers) { items, folderIds ->
            items
                .filter { filterItem ->
                    filterItem.filters
                        .any { filter ->
                            val filterFixed = filter as DFilter.ById
                            require(filterFixed.what == DFilter.ById.What.FOLDER)
                            filterFixed.id in folderIds
                        }
                }
        }
        .aaa(
            sectionId = folderSectionId,
            sectionTitle = translate(Res.strings.folder),
        )
        .filterSection(params.section.folder)

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
                        title = translate(Res.strings.collection_none),
                    )
        }
        .combine(filterCollectionsWithCiphers) { items, collectionIds ->
            items
                .filter { filterItem ->
                    filterItem.filters
                        .any { filter ->
                            val filterFixed = filter as DFilter.ById
                            require(filterFixed.what == DFilter.ById.What.COLLECTION)
                            filterFixed.id in collectionIds
                        }
                }
        }
        .aaa(
            sectionId = collectionSectionId,
            sectionTitle = translate(Res.strings.collection),
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
                        title = translate(Res.strings.organization_none),
                    )
        }
        .combine(filterOrganizationsWithCiphers) { items, organizationIds ->
            items
                .filter { filterItem ->
                    filterItem.filters
                        .any { filter ->
                            val filterFixed = filter as DFilter.ById
                            require(filterFixed.what == DFilter.ById.What.ORGANIZATION)
                            filterFixed.id in organizationIds
                        }
                }
        }
        .aaa(
            sectionId = organizationSectionId,
            sectionTitle = translate(Res.strings.organization),
        )
        .filterSection(params.section.organization)

    val filterMiscAll = listOf(
        createFilterAction(
            sectionId = miscSectionId,
            filter = setOf(
                DFilter.ByOtp,
            ),
            filterSectionId = "$miscSectionId.otp",
            title = translate(Res.strings.one_time_password),
            icon = Icons.Outlined.KeyguardTwoFa,
        ),
        createFilterAction(
            sectionId = miscSectionId,
            filter = setOf(
                DFilter.ByAttachments,
            ),
            filterSectionId = "$miscSectionId.attachments",
            title = translate(Res.strings.attachments),
            icon = Icons.Outlined.KeyguardAttachment,
        ),
        createFilterAction(
            sectionId = miscSectionId,
            filter = setOf(
                DFilter.ByPasskeys,
            ),
            filterSectionId = "$miscSectionId.passkeys",
            title = translate(Res.strings.passkeys),
            icon = Icons.Outlined.Key,
        ),
        createFilterAction(
            sectionId = miscSectionId,
            filter = setOf(
                DFilter.ByReprompt(reprompt = true),
            ),
            filterSectionId = "$miscSectionId.reprompt",
            title = "Auth re-prompt",
            icon = Icons.Outlined.Lock,
        ),
        createFilterAction(
            sectionId = miscSectionId,
            filter = setOf(
                DFilter.BySync(synced = false),
            ),
            filterSectionId = "$miscSectionId.sync",
            title = "Un-synced",
            icon = Icons.Outlined.CloudOff,
        ),
        createFilterAction(
            sectionId = miscSectionId,
            filter = setOf(
                DFilter.ByError(error = true),
            ),
            filterSectionId = "$miscSectionId.error",
            title = "Failed",
            icon = Icons.Outlined.ErrorOutline,
        ),
        createFilterAction(
            sectionId = miscSectionId,
            filter = setOf(
                DFilter.ByIgnoredAlerts,
            ),
            filterSectionId = "$miscSectionId.watchtower_alerts",
            title = translate(Res.strings.ignored_alerts),
            icon = Icons.Outlined.NotificationsOff,
        ),
    )

    val filterMiscListFlow = flowOf(Unit)
        .map {
            filterMiscAll
        }
        .aaa(
            sectionId = miscSectionId,
            sectionTitle = translate(Res.strings.misc),
            collapse = false,
        )
        .filterSection(params.section.misc)

    val filterCustomTypesAll = listOf(
        DSecret.Type.Login to createTypeFilterAction(
            sectionId = customSectionId,
            type = DSecret.Type.Login,
        ),
        DSecret.Type.Card to createTypeFilterAction(
            sectionId = customSectionId,
            type = DSecret.Type.Card,
        ),
        DSecret.Type.Identity to createTypeFilterAction(
            sectionId = customSectionId,
            type = DSecret.Type.Identity,
        ),
        DSecret.Type.SecureNote to createTypeFilterAction(
            sectionId = customSectionId,
            type = DSecret.Type.SecureNote,
        ),
    )

    val filterCustomAll = listOf(
        createFilterAction(
            sectionId = customSectionId,
            filter = setOf(
                DFilter.ByOtp,
            ),
            filterSectionId = "$miscSectionId.otp",
            title = translate(Res.strings.one_time_password),
            icon = Icons.Outlined.KeyguardTwoFa,
        ),
    )

    if (params.deeplinkCustomFilter == "2fa") {
        input.onToggle(
            "$miscSectionId.otp",
            setOf(
                DFilter.ByOtp,
            ),
        )
    }

    val filterCustomListFlow = flowOf(Unit)
        .map {
            filterCustomTypesAll.map { it.second } + filterCustomAll
        }
        .aaa(
            sectionId = customSectionId,
            sectionTitle = translate(Res.strings.custom),
            collapse = false,
        )
        .filterSection(params.section.custom)

    return combine(
        filterCustomListFlow,
        filterAccountListFlow,
        filterOrganizationListFlow,
        filterTypeListFlow,
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
                .mapNotNull {
                    when (it) {
                        is FilterItem.Section -> null
                        is FilterItem.Item -> it.takeIf { it.checked }
                            ?.sectionId
                    }
                }
                .toSet()

            val out = mutableListOf<FilterItem>()
            items.forEach { item ->
                when (item) {
                    is FilterItem.Section -> out += item
                    is FilterItem.Item -> {
                        val fastEnabled = item.checked ||
                                // If one of the items in a section is enabled, then
                                // enable the whole section.
                                item.sectionId in checkedSectionIds
                        val enabled = fastEnabled || kotlin.run {
                            item.filters
                                .any { filter ->
                                    val filterPredicate = filter.prepare(directDI, outputCiphers)
                                    outputCiphers.any(filterPredicate)
                                }
                        }

                        if (enabled) {
                            out += item
                            return@forEach
                        }

                        out += item.copy(onClick = null)
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
            )
        }
        .flowOn(Dispatchers.Default)
}
