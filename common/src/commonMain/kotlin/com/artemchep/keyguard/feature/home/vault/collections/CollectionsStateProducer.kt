package com.artemchep.keyguard.feature.home.vault.collections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.collection.CollectionRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardCipher
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
import java.util.UUID

@Composable
fun collectionsScreenState(
    args: CollectionsRoute.Args,
) = with(localDI().direct) {
    collectionsScreenState(
        args = args,
        getOrganizations = instance(),
        getCollections = instance(),
        getCiphers = instance(),
        getCanWrite = instance(),
    )
}

@Composable
fun collectionsScreenState(
    args: CollectionsRoute.Args,
    getOrganizations: GetOrganizations,
    getCollections: GetCollections,
    getCiphers: GetCiphers,
    getCanWrite: GetCanWrite,
): CollectionsState = produceScreenState(
    key = "collections",
    initial = CollectionsState(),
    args = arrayOf(
        args,
        getCollections,
        getOrganizations,
        getCiphers,
        getCanWrite,
    ),
) {
    fun checkAccountId(accountId: String) = accountId == args.accountId.id

    fun checkOrganizationId(organizationId: String?) =
        args.organizationId == null ||
                args.organizationId == organizationId

    data class CollectionWithCiphers(
        val collection: DCollection,
        val organization: DOrganization?,
        val ciphers: List<DSecret>,
    )

    val comparator = Comparator { a: CollectionWithCiphers, b: CollectionWithCiphers ->
        var out = compareValues(a.organization, b.organization)
        if (out == 0) {
            out = compareValues(a.collection, b.collection)
        }
        out
    }

    val collectionsFlow = getCollections()
        .map { collections ->
            collections
                .filter {
                    checkAccountId(it.accountId) &&
                            checkOrganizationId(it.organizationId)
                }
        }
        .distinctUntilChanged()
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)
    val organizationsMapFlow = getOrganizations()
        .map { organizations ->
            organizations
                .filter {
                    checkAccountId(it.accountId) &&
                            checkOrganizationId(it.id)
                }
        }
        .distinctUntilChanged()
        .map { organizations ->
            organizations
                // can ot have collisions because we filter by account id
                .associateBy { it.id }
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)
    val ciphersMapByCollectionIdFlow = getCiphers()
        .map { ciphers ->
            ciphers
                .filter {
                    it.deletedDate == null &&
                            checkAccountId(it.accountId) &&
                            checkOrganizationId(it.organizationId)
                }
        }
        .distinctUntilChanged()
        .map { ciphers ->
            ciphers
                .flatMap { cipher ->
                    cipher
                        .collectionIds
                        .map { collectionId ->
                            collectionId to cipher
                        }
                }
                .groupBy { it.first }
                .mapValues { entry ->
                    entry
                        .value
                        .map { collectionCipherPair ->
                            collectionCipherPair.second
                        }
                }
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)

    val collectionsWithCiphersFolder = combine(
        collectionsFlow,
        organizationsMapFlow,
        ciphersMapByCollectionIdFlow,
    ) { collections, organizationsMap, ciphersMapByCollectionId ->
        collections
            .map { collection ->
                val organization = organizationsMap[collection.organizationId]
                val ciphers = ciphersMapByCollectionId[collection.id].orEmpty()
                CollectionWithCiphers(
                    collection = collection,
                    organization = organization,
                    ciphers = ciphers,
                )
            }
            .sortedWith(comparator)
    }

    val selectionHandle = selectionHandle("selection")
    val selectedCollectionsWithCiphersFlow = collectionsWithCiphersFolder
        .combine(selectionHandle.idsFlow) { collectionsWithCiphers, selectedCollectionIds ->
            selectedCollectionIds
                .mapNotNull { selectedCollectionId ->
                    val collectionWithCiphers = collectionsWithCiphers
                        .firstOrNull { it.collection.id == selectedCollectionId }
                        ?: return@mapNotNull null
                    selectedCollectionId to collectionWithCiphers
                }
                .toMap()
        }
        .distinctUntilChanged()
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)

    val selectionFlow = combine(
        collectionsFlow
            .map { collections ->
                collections
                    .map { it.id }
                    .toSet()
            }
            .distinctUntilChanged(),
        selectedCollectionsWithCiphersFlow,
        getCanWrite(),
    ) { collectionIds, selectedCollections, canWrite ->
        if (selectedCollections.isEmpty()) {
            return@combine null
        }

        val selectedFolderIds = selectedCollections.keys
        val actions = mutableListOf<FlatItemAction>()
        // An option to view all the items that belong
        // to these collections.
        val ciphersCount = selectedCollections
            .asSequence()
            .flatMap { it.value.ciphers }
            .distinct()
            .count()
        if (ciphersCount > 0) {
            actions += FlatItemAction(
                icon = Icons.Outlined.KeyguardCipher,
                title = translate(Res.strings.items),
                trailing = {
                    ChevronIcon()
                },
                onClick = {
                    val collections = selectedCollections.values
                        .map { it.collection }
                    val route = VaultRoute.by(
                        translator = this,
                        collections = collections,
                    )
                    val intent = NavigationIntent.NavigateToRoute(route)
                    navigate(intent)
                },
            )
        }
        Selection(
            count = selectedCollections.size,
            actions = actions.toImmutableList(),
            onSelectAll = selectionHandle::setSelection
                .partially1(collectionIds)
                .takeIf {
                    collectionIds.size > selectedFolderIds.size
                },
            onClear = selectionHandle::clearSelection,
        )
    }
    val contentFlow = combine(
        collectionsWithCiphersFolder,
        selectedCollectionsWithCiphersFlow,
        getCanWrite(),
    ) { collectionsWithCiphers, selectedCollectionIds, canWrite ->
        val selecting = selectedCollectionIds.isNotEmpty()
        val decorator = if (args.organizationId != null) {
            // We already know the organization, no need to add it
            // to the list.
            NoDecorator
        } else {
            OrganizationDecorator()
        }
        val items = collectionsWithCiphers
            .asSequence()
            .map { collectionWithCiphers ->
                val collection = collectionWithCiphers.collection
                val ciphers = collectionWithCiphers.ciphers
                val selected = collection.id in selectedCollectionIds

                val actions = buildContextItems {
                    section {
                        // An option to view all the items that belong
                        // to this cipher.
                        if (ciphers.isNotEmpty()) {
                            this += FlatItemAction(
                                icon = Icons.Outlined.KeyguardCipher,
                                title = translate(Res.strings.items),
                                onClick = {
                                    val route = VaultRoute.by(
                                        translator = this@produceScreenState,
                                        collection = collection,
                                    )
                                    val intent = NavigationIntent.NavigateToRoute(route)
                                    navigate(intent)
                                },
                            )
                        }
                    }
                    section {
                        this += FlatItemAction(
                            icon = Icons.Outlined.Info,
                            title = translate(Res.strings.info),
                            onClick = {
                                val intent = NavigationIntent.NavigateToRoute(
                                    CollectionRoute(
                                        args = CollectionRoute.Args(
                                            collectionId = collection.id,
                                        ),
                                    ),
                                )
                                navigate(intent)
                            },
                        )
                    }
                }
                CollectionsState.Content.Item.Collection(
                    key = collection.id,
                    title = collection.name,
                    ciphers = collectionWithCiphers.ciphers.size,
                    organization = collectionWithCiphers.organization,
                    selecting = selecting,
                    selected = selected,
                    actions = actions.toImmutableList(),
                    onClick = if (selecting) {
                        // lambda
                        selectionHandle::toggleSelection.partially1(collection.id)
                    } else {
                        null
                    },
                    onLongClick = if (selecting) {
                        null
                    } else {
                        // lambda
                        selectionHandle::toggleSelection.partially1(collection.id)
                    },
                )
            }
            .flatMap { item ->
                sequence<CollectionsState.Content.Item> {
                    val section = decorator.getOrNull(item)
                    if (section != null) yield(section)
                    yield(item)
                }
            }
            .toImmutableList()
        CollectionsState.Content(
            title = CollectionsState.Content.Title(),
            items = items,
        )
    }
    combine(
        selectionFlow,
        contentFlow,
    ) { selection, content ->
        CollectionsState(
            selection = selection,
            content = Loadable.Ok(content),
        )
    }
}

private interface Decorator {
    fun getOrNull(item: CollectionsState.Content.Item.Collection): CollectionsState.Content.Item?
}

private object NoDecorator : Decorator {
    override fun getOrNull(item: CollectionsState.Content.Item.Collection) = null
}

private class OrganizationDecorator : Decorator {
    private val seenOrganizationIds = mutableSetOf<String>()

    /**
     * Last shown character, used to not repeat the sections
     * if it stays the same.
     */
    private var lastOrganization: DOrganization? = null

    override fun getOrNull(item: CollectionsState.Content.Item.Collection): CollectionsState.Content.Item? {
        val organization = item.organization
        if (organization?.id == lastOrganization?.id) {
            return null
        }

        lastOrganization = organization
        if (organization != null) {
            val itemKey = if (organization.id in seenOrganizationIds) {
                val randomId = UUID.randomUUID().toString()
                "duplicate.$randomId"
            } else {
                organization.id
            }
            // mark as seen, so next time we generate a new id for the section
            seenOrganizationIds += organization.id

            return CollectionsState.Content.Item.Section(
                key = "decorator.organization.$itemKey",
                text = organization.name,
            )
        }
        return null
    }
}
