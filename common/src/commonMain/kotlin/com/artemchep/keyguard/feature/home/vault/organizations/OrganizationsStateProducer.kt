package com.artemchep.keyguard.feature.home.vault.organizations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRoute
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsState
import com.artemchep.keyguard.feature.home.vault.organization.OrganizationRoute
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardCipher
import com.artemchep.keyguard.ui.icons.KeyguardCollection
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun organizationsScreenState(
    args: OrganizationsRoute.Args,
) = with(localDI().direct) {
    organizationsScreenState(
        args = args,
        getOrganizations = instance(),
        getCollections = instance(),
        getCiphers = instance(),
        getCanWrite = instance(),
    )
}

@Composable
fun organizationsScreenState(
    args: OrganizationsRoute.Args,
    getOrganizations: GetOrganizations,
    getCollections: GetCollections,
    getCiphers: GetCiphers,
    getCanWrite: GetCanWrite,
): OrganizationsState = produceScreenState(
    key = "organizations",
    initial = OrganizationsState(),
    args = arrayOf(
        args,
        getOrganizations,
        getCollections,
        getCiphers,
        getCanWrite,
    ),
) {
    data class OrganizationWithCiphers(
        val organization: DOrganization,
        val collections: List<DCollection>,
        val ciphers: List<DSecret>,
    )

    val organizationsComparator = Comparator { a: DOrganization, b: DOrganization ->
        AlphabeticalSort.compareStr(a.name, b.name)
    }
    val organizationsFlow = getOrganizations()
        .map { organizations ->
            organizations
                .filter { it.accountId == args.accountId.id }
                .sortedWith(organizationsComparator)
        }
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)
    val collectionsMapByOrganizationIdFlow = getCollections()
        .map { collections ->
            collections
                .groupBy { it.organizationId }
        }
        .distinctUntilChanged()
    val ciphersMapByOrganizationIdFlow = getCiphers()
        .map { ciphers ->
            ciphers
                .filter { it.deletedDate == null }
                .groupBy { it.organizationId }
        }
        .distinctUntilChanged()
    val organizationsWithCiphersFlow = combine(
        organizationsFlow,
        collectionsMapByOrganizationIdFlow,
        ciphersMapByOrganizationIdFlow,
    ) { organizations, collectionsMapByOrganizationId, ciphersMapByOrganizationId ->
        organizations
            .map { organization ->
                val collections = collectionsMapByOrganizationId[organization.id].orEmpty()
                val ciphers = ciphersMapByOrganizationId[organization.id].orEmpty()
                OrganizationWithCiphers(
                    organization = organization,
                    collections = collections,
                    ciphers = ciphers,
                )
            }
    }

    val selectionHandle = selectionHandle("selection")
    val selectedOrganizationsWithCiphersFlow = organizationsWithCiphersFlow
        .combine(selectionHandle.idsFlow) { organizationsWithCiphers, selectedOrganizationIds ->
            selectedOrganizationIds
                .mapNotNull { selectedOrganizationId ->
                    val organizationWithCiphers = organizationsWithCiphers
                        .firstOrNull { it.organization.id == selectedOrganizationId }
                        ?: return@mapNotNull null
                    selectedOrganizationId to organizationWithCiphers
                }
                .toMap()
        }
        .distinctUntilChanged()
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)

    val selectionFlow = combine(
        organizationsFlow
            .map { organizations ->
                organizations
                    .map { it.id }
                    .toSet()
            }
            .distinctUntilChanged(),
        selectedOrganizationsWithCiphersFlow,
        getCanWrite(),
    ) { organizationsIds, selectedOrganizations, canWrite ->
        if (selectedOrganizations.isEmpty()) {
            return@combine null
        }

        val selectedOrganizationIds = selectedOrganizations.keys
        val actions = mutableListOf<FlatItemAction>()
        Selection(
            count = selectedOrganizations.size,
            actions = actions.toImmutableList(),
            onSelectAll = selectionHandle::setSelection
                .partially1(organizationsIds)
                .takeIf {
                    organizationsIds.size > selectedOrganizationIds.size
                },
            onClear = selectionHandle::clearSelection,
        )
    }
    val contentFlow = combine(
        organizationsWithCiphersFlow,
        selectedOrganizationsWithCiphersFlow,
        getCanWrite(),
    ) { organizationsWithCiphers, selectedOrganizationIds, canWrite ->
        val selecting = selectedOrganizationIds.isNotEmpty()
        val items = organizationsWithCiphers
            .map { organizationWithCiphers ->
                val organization = organizationWithCiphers.organization
                val collections = organizationWithCiphers.collections
                val ciphers = organizationWithCiphers.ciphers
                val selected = organization.id in selectedOrganizationIds

                val actions = buildContextItems {
                    section {
                        // An option to view all the items that belong
                        // to this cipher.
                        if (ciphers.isNotEmpty()) {
                            this += FlatItemAction(
                                icon = Icons.Outlined.KeyguardCipher,
                                title = Res.string.items.wrap(),
                                trailing = {
                                    ChevronIcon()
                                },
                                onClick = onClick {
                                    val route = VaultRoute.by(
                                        translator = this@produceScreenState,
                                        organization = organization,
                                    )
                                    val intent = NavigationIntent.NavigateToRoute(route)
                                    navigate(intent)
                                },
                            )
                        }
                        // An option to view all the items that belong
                        // to this cipher.
                        if (collections.isNotEmpty()) {
                            this += FlatItemAction(
                                icon = Icons.Outlined.KeyguardCollection,
                                title = Res.string.collections.wrap(),
                                trailing = {
                                    ChevronIcon()
                                },
                                onClick = {
                                    val intent = NavigationIntent.NavigateToRoute(
                                        CollectionsRoute(
                                            args = CollectionsRoute.Args(
                                                accountId = organization.accountId.let(::AccountId),
                                                organizationId = organization.id,
                                            ),
                                        ),
                                    )
                                    navigate(intent)
                                },
                            )
                        }
                    }
                    section {
                        this += FlatItemAction(
                            icon = Icons.Outlined.Info,
                            title = Res.string.info.wrap(),
                            onClick = {
                                val intent = NavigationIntent.NavigateToRoute(
                                    OrganizationRoute(
                                        args = OrganizationRoute.Args(
                                            organizationId = organization.id,
                                        ),
                                    ),
                                )
                                navigate(intent)
                            },
                        )
                    }
                }
                OrganizationsState.Content.Item(
                    key = organization.id,
                    title = organization.name,
                    accentColors = organization.accentColor,
                    ciphers = organizationWithCiphers.ciphers.size,
                    selecting = selecting,
                    selected = selected,
                    actions = actions.toImmutableList(),
                    onClick = if (selecting) {
                        // lambda
                        selectionHandle::toggleSelection.partially1(organization.id)
                    } else {
                        null
                    },
                    onLongClick = if (selecting) {
                        null
                    } else {
                        // lambda
                        selectionHandle::toggleSelection.partially1(organization.id)
                    },
                )
            }
            .toList()
        val itemsReShaped = items
            .mapIndexed { index, item ->
                when (item) {
                    is OrganizationsState.Content.Item -> {
                        val shapeState = getShapeState(
                            list = items,
                            index = index,
                            predicate = { el, offset ->
                                el is OrganizationsState.Content.Item
                            },
                        )
                        item.copy(
                            shapeState = shapeState,
                        )
                    }

                    else -> item
                }
            }
            .toImmutableList()
        OrganizationsState.Content(
            items = itemsReShaped,
        )
    }
    combine(
        selectionFlow,
        contentFlow,
    ) { selection, content ->
        OrganizationsState(
            selection = selection,
            content = Loadable.Ok(content),
        )
    }
}
