package com.artemchep.keyguard.feature.filter.view

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.runtime.Composable
import arrow.core.partially1
import arrow.core.right
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.service.filter.RemoveCipherFilterById
import com.artemchep.keyguard.common.service.filter.RenameCipherFilter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.util.flow.foldAsList
import com.artemchep.keyguard.feature.filter.util.CipherFilterUtil
import com.artemchep.keyguard.feature.filter.util.addShortcutActionOrNull
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.screen.FilterSection
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.autoclose.launchAutoPopSelfHandler
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.iconSmall
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceCipherFilterViewState(
    args: CipherFilterViewDialogRoute.Args,
) = with(localDI().direct) {
    produceCipherFilterViewState(
        args = args,
        getCipherFilters = instance(),
        removeCipherFilterById = instance(),
        renameCipherFilter = instance(),
        getAccounts = instance(),
        getProfiles = instance(),
        getOrganizations = instance(),
        getCollections = instance(),
        getFolders = instance(),
        getCiphers = instance(),
    )
}

@Composable
fun produceCipherFilterViewState(
    args: CipherFilterViewDialogRoute.Args,
    getCipherFilters: GetCipherFilters,
    removeCipherFilterById: RemoveCipherFilterById,
    renameCipherFilter: RenameCipherFilter,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getOrganizations: GetOrganizations,
    getCollections: GetCollections,
    getFolders: GetFolders,
    getCiphers: GetCiphers,
): Loadable<CipherFilterViewState> = produceScreenState(
    key = "cipher_filter_view",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val filterFlow = getCipherFilters()
        .map { filters ->
            filters
                .firstOrNull { it.idRaw == args.model.idRaw }
        }
        .shareInScreenScope()
    launchAutoPopSelfHandler(filterFlow)

    val profilesMapFlow = getProfiles()
        .map { profiles ->
            profiles.associateBy { it.accountId }
        }
    val organizationsMapFlow = getOrganizations()
        .map { organizations ->
            organizations
                .associate {
                    it.id to it.name
                }
        }
    val collectionsMapFlow = getCollections()
        .map { collections ->
            collections
                .associate {
                    it.id to it.name
                }
        }
    val foldersMapFlow = getFolders()
        .map { folders ->
            folders
                .associate {
                    it.id to it.name
                }
        }
    val ciphersMapFlow = getCiphers()
        .map { ciphers ->
            ciphers
                .associate {
                    it.id to it.name
                }
        }

    val listFlow = filterFlow
        .map { filter ->
            filter
                ?: return@map emptyList<Flow<FilterItem>>()

            val l = mutableListOf<Flow<FilterItem>>()
            FilterSection.entries.forEach { filterSection ->
                val filterSet = filter.filter[filterSection.id]
                    ?: return@forEach
                // Should never happen, but the structure
                // definitely allows it.
                if (filterSet.isEmpty()) {
                    return@forEach
                }

                fun createFilterItem(
                    filter: DFilter.Primitive,
                    leading: (@Composable () -> Unit)?,
                    title: String,
                    text: String? = null,
                ): FilterItem.Item {
                    return FilterItem.Item(
                        sectionId = filterSection.id,
                        filterSectionId = filterSection.id,
                        filter = FilterItem.Item.Filter.Toggle(
                            filters = setOf(filter),
                        ),
                        checked = false,
                        enabled = true,
                        fill = false,
                        leading = leading,
                        title = title,
                        text = text,
                        onClick = null,
                    )
                }

                l += FilterItem.Section(
                    sectionId = filterSection.id,
                    text = translate(filterSection.title),
                    expandable = false,
                    onClick = null,
                ).let(::flowOf)
                filterSet.forEach { f ->
                    when (f) {
                        is DFilter.PrimitiveSimple -> {
                            l += createFilterItem(
                                filter = f,
                                leading = f.content.icon
                                    ?.let {
                                        iconSmall(it)
                                    },
                                title = translate(f.content.title),
                            ).let(::flowOf)
                        }

                        is DFilter.ById -> {
                            val sourceFlow = when (f.what) {
                                DFilter.ById.What.ACCOUNT -> {
                                    l += profilesMapFlow
                                        .map { state -> state[f.id] }
                                        .distinctUntilChanged()
                                        .map { profile ->
                                            createFilterItem(
                                                filter = f,
                                                leading = null,
                                                title = profile?.email.orEmpty(),
                                                text = profile?.accountHost.orEmpty(),
                                            )
                                        }
                                    return@forEach
                                }

                                DFilter.ById.What.FOLDER -> {
                                    if (f.id == null) {
                                        l += createFilterItem(
                                            filter = f,
                                            leading = iconSmall(Icons.Outlined.FolderOff),
                                            title = translate(Res.string.folder_none),
                                        ).let(::flowOf)
                                        return@forEach
                                    }

                                    foldersMapFlow
                                }

                                DFilter.ById.What.COLLECTION -> {
                                    if (f.id == null) {
                                        l += createFilterItem(
                                            filter = f,
                                            leading = null,
                                            title = translate(Res.string.collection_none),
                                        ).let(::flowOf)
                                        return@forEach
                                    }

                                    collectionsMapFlow
                                }

                                DFilter.ById.What.ORGANIZATION -> {
                                    if (f.id == null) {
                                        l += createFilterItem(
                                            filter = f,
                                            leading = null,
                                            title = translate(Res.string.organization_none),
                                        ).let(::flowOf)
                                        return@forEach
                                    }

                                    organizationsMapFlow
                                }

                                DFilter.ById.What.CIPHER -> ciphersMapFlow
                            }

                            l += sourceFlow
                                .map { state -> state[f.id] }
                                .distinctUntilChanged()
                                .map { name ->
                                    createFilterItem(
                                        filter = f,
                                        leading = null,
                                        title = name.orEmpty(),
                                    )
                                }
                        }
                    }
                }
            }
            l
        }
        .flatMapLatest {
            it.foldAsList()
        }
        .map {
            CipherFilterViewState.Filter(
                items = it.toPersistentList(),
            )
        }
    val toolbarFlow = filterFlow
        .map { filter ->
            val actions = run {
                filter
                    ?: return@run persistentListOf()
                buildContextItems {
                    section {
                        this += CipherFilterUtil.addShortcutActionOrNull(
                            filter = filter,
                        )
                    }
                    section {
                        this += FlatItemAction(
                            icon = Icons.Outlined.Edit,
                            title = Res.string.edit.wrap(),
                            onClick = onClick {
                                CipherFilterUtil.onRename(
                                    renameCipherFilter = renameCipherFilter,
                                    model = filter,
                                )
                            },
                        )
                    }
                    section {
                        val filterAsItems = listOf(
                            filter,
                        )
                        this += FlatItemAction(
                            icon = Icons.Outlined.Delete,
                            title = Res.string.delete.wrap(),
                            onClick = onClick {
                                CipherFilterUtil.onDeleteByItems(
                                    removeCipherFilterById = removeCipherFilterById,
                                    items = filterAsItems,
                                )
                            },
                        )
                    }
                }
            }
            CipherFilterViewState.Toolbar(
                model = filter,
                actions = actions,
            )
        }
        .stateIn(screenScope)

    filterFlow
        .map { model ->
            val content = CipherFilterViewState.Content(
                model = model,
            )
            val state = CipherFilterViewState(
                toolbarFlow = toolbarFlow,
                filterFlow = listFlow,
                content = content
                    .right(),
                onClose = {
                    navigatePopSelf()
                },
            )
            Loadable.Ok(state)
        }
}
