package com.artemchep.keyguard.feature.add

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.model.displayName
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationResult
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

interface OwnershipHandle<T : OwnershipState> {
    val profilesFlow: Flow<List<DProfile>>
    val ciphersFlow: Flow<List<DSecret>>

    val stateSink: MutableStateFlow<T>
}

/**
 * [LeParcelable] because the state is persisted through `mutablePersistedFlow`, which requires
 * a bundle-compatible type.
 */
interface OwnershipState : LeParcelable {
    val accountId: String?
}

suspend fun <T : OwnershipState> RememberStateFlowScope.ownershipHandle(
    key: String,
    profilesFlow: Flow<List<DProfile>>,
    ciphersFlow: Flow<List<DSecret>>,
    initialValue: T?,
    factory: (String?) -> T,
): OwnershipHandle<T> {
    val disk = loadDiskHandle(key)
    val accountIdSink = mutablePersistedFlow<String?>(
        key = "ownership.account_id",
        storage = PersistedStorage.InDisk(disk),
    ) { null }

    val initialState = kotlin.run {
        if (initialValue != null) {
            return@run initialValue
        }

        // Make an account that has the most ciphers a
        // default account.
        val accountId = ioEffect {
            val accountIds = profilesFlow.toIO().bind()
                .asSequence()
                .map { profile ->
                    profile.accountId()
                }
                .toSet()

            fun String.takeIfAccountIdExists() = this
                .takeIf { id ->
                    id in accountIds
                }

            val lastAccountId = accountIdSink.value?.takeIfAccountIdExists()
            if (lastAccountId != null) {
                return@ioEffect lastAccountId
            }

            val ciphers = ciphersFlow.toIO().bind()
            ciphers
                .asSequence()
                .map {
                    val groupKey = it.accountId
                    val score = 1.0 +
                            (if (it.organizationId == null) 0.8 else 0.0) +
                            (if (it.folderId != null) 0.2 else 0.0)
                    groupKey to score
                }
                .groupBy { it.first }
                .mapValues { it.value.sumOf { it.second } }
                // the one that has the highest score
                .maxByOrNull { entry -> entry.value }
                // account id
                ?.key
                ?.takeIfAccountIdExists()
                // Fallback to the first account ID we fetched in the
                // case there are not ciphers in the vault yet.
                ?: accountIds.firstOrNull()
        }.attempt().bind().getOrNull()

        factory(
            accountId,
        )
    }
    val sink = mutablePersistedFlow("ownership") {
        initialState
    }

    // If we are creating a new item, then remember the
    // last selected account to pre-select it next time.
    sink
        .map { it.accountId }
        .onEach(accountIdSink::value::set)
        .launchIn(screenScope)

    return object : OwnershipHandle<T> {
        override val profilesFlow: Flow<List<DProfile>> get() = profilesFlow

        override val ciphersFlow: Flow<List<DSecret>> get() = ciphersFlow

        override val stateSink: MutableStateFlow<T> get() = sink
    }
}

context(stateScope: RememberStateFlowScope)
fun <T : OwnershipState> OwnershipHandle<T>.accountFlow(
    readOnly: Boolean,
): Flow<AddStateOwnershipElementHolder<String?>> = ownershipAccountFlow(
    accountIdFlow = stateSink
        .map { it.accountId },
    profilesFlow = profilesFlow,
    readOnly = readOnly,
)

context(stateScope: RememberStateFlowScope)
private fun ownershipAccountFlow(
    accountIdFlow: Flow<String?>,
    profilesFlow: Flow<List<DProfile>>,
    readOnly: Boolean,
): Flow<AddStateOwnershipElementHolder<String?>> = with(stateScope) {
    combine(
        accountIdFlow
            .distinctUntilChanged(),
        profilesFlow,
    ) { accountId, profiles ->
        if (accountId == null) {
            val item = AddStateOwnership.Element.Item(
                key = "account.empty",
                title = translate(Res.string.account_none),
                stub = true,
            )
            val el = AddStateOwnership.Element(
                readOnly = readOnly,
                items = listOf(item),
            )
            return@combine AddStateOwnershipElementHolder(
                value = null,
                element = el,
            )
        }
        val profileOrNull = profiles
            .firstOrNull { it.accountId() == accountId }
        val el = AddStateOwnership.Element(
            readOnly = readOnly,
            items = listOfNotNull(profileOrNull)
                .map { account ->
                    val key = "account.${account.accountId()}"
                    AddStateOwnership.Element.Item(
                        key = key,
                        title = account.displayName,
                        text = account.accountHost,
                        accentColors = account.accentColor,
                    )
                },
        )
        AddStateOwnershipElementHolder(
            value = accountId,
            element = el,
        )
    }
}

/**
 * Produces the full ownership of an item: its account, organization,
 * collections and folder, and the "Save to" dialog that changes them.
 * A [readOnly] ownership only lets the user change the folder.
 * [readOnlyAccountFlow] can pin just the account while the other choices remain editable.
 */
@Suppress("LongMethod", "LongParameterList")
context(stateScope: RememberStateFlowScope)
fun <R> ownershipPickerFlow(
    ownershipFlow: Flow<CreateRequest.Ownership2>,
    profilesFlow: Flow<List<DProfile>>,
    organizationsFlow: Flow<List<DOrganization>>,
    collectionsFlow: Flow<List<DCollection>>,
    foldersFlow: Flow<List<DFolder>>,
    readOnly: Boolean,
    readOnlyAccountFlow: Flow<Boolean> = flowOf(readOnly),
    onChange: (OrganizationConfirmationResult.Confirm) -> Unit,
    transform: (CreateRequest.Ownership2, AddStateOwnership) -> R,
): Flow<R> = with(stateScope) {
    val accountFlow = ownershipAccountFlow(
        accountIdFlow = ownershipFlow
            .map { it.accountId },
        profilesFlow = profilesFlow,
        readOnly = readOnly,
    )

    val organizationFlow = combine(
        ownershipFlow
            .map { it.organizationId }
            .distinctUntilChanged(),
        organizationsFlow,
    ) { organizationId, organizations ->
        if (organizationId == null) {
            val item = AddStateOwnership.Element.Item(
                key = "organization.empty",
                title = translate(Res.string.organization_none),
                stub = true,
            )
            val el = AddStateOwnership.Element(
                readOnly = readOnly,
                items = listOf(item),
            )
            return@combine AddStateOwnershipElementHolder(
                value = null,
                element = el,
            )
        }
        val organizationOrNull = organizations
            .firstOrNull { it.id == organizationId }
        val el = AddStateOwnership.Element(
            readOnly = readOnly,
            items = listOfNotNull(organizationOrNull)
                .map { organization ->
                    val key = "organization.${organization.id}"
                    AddStateOwnership.Element.Item(
                        key = key,
                        title = organization.name,
                    )
                },
        )
        AddStateOwnershipElementHolder(
            value = organizationId,
            element = el,
        )
    }

    val collectionFlow = combine(
        ownershipFlow
            .map { it.collectionIds }
            .distinctUntilChanged(),
        collectionsFlow,
    ) { collectionIds, collections ->
        if (collectionIds.isEmpty()) {
            return@combine AddStateOwnershipElementHolder(
                value = emptySet(),
                element = null,
            )
        }

        val selectedCollections = collections
            .sortedWith(StringComparatorIgnoreCase { it.name })
            .filter { it.id in collectionIds }
        val el = AddStateOwnership.Element(
            readOnly = readOnly,
            items = selectedCollections
                .map { collection ->
                    val key = "collection.${collection.id}"
                    AddStateOwnership.Element.Item(
                        key = key,
                        title = collection.name,
                    )
                },
        )
        AddStateOwnershipElementHolder(
            value = collectionIds,
            element = el,
        )
    }

    val folderFlow = combine(
        ownershipFlow
            .map { it.folder }
            .distinctUntilChanged(),
        foldersFlow,
    ) { selectedFolder, folders ->
        when (selectedFolder) {
            is FolderInfo.None -> {
                val item = AddStateOwnership.Element.Item(
                    key = "folder.empty",
                    title = translate(Res.string.folder_none),
                    stub = true,
                )
                val el = AddStateOwnership.Element(
                    readOnly = false,
                    items = listOf(item),
                )
                return@combine AddStateOwnershipElementHolder(
                    value = selectedFolder,
                    element = el,
                )
            }

            is FolderInfo.New -> {
                val item = AddStateOwnership.Element.Item(
                    key = "folder.new",
                    title = selectedFolder.name,
                    stub = true,
                )
                val el = AddStateOwnership.Element(
                    readOnly = false,
                    items = listOf(item),
                )
                return@combine AddStateOwnershipElementHolder(
                    value = selectedFolder,
                    element = el,
                )
            }

            is FolderInfo.Id -> {
                val selectedFolderOrNull = folders
                    .firstOrNull { it.id == selectedFolder.id }

                // This might happen if the folder associated with the cipher was deleted.
                // In that case the cipher continues to have the folder-id set, but we
                // surface. The possible issue here is that if the "folders" are not complete
                // yet, then the cipher will be shown as "No folder" for small period of time.
                val el = if (selectedFolderOrNull == null) {
                    val item = AddStateOwnership.Element.Item(
                        key = "folder.empty",
                        title = translate(Res.string.folder_none),
                        stub = true,
                    )
                    AddStateOwnership.Element(
                        readOnly = false,
                        items = listOf(item),
                    )
                } else {
                    AddStateOwnership.Element(
                        readOnly = false,
                        items = listOfNotNull(selectedFolderOrNull)
                            .map { folder ->
                                val key = "folder.${folder.id}"
                                AddStateOwnership.Element.Item(
                                    key = key,
                                    title = folder.name,
                                )
                            },
                    )
                }
                AddStateOwnershipElementHolder(
                    value = selectedFolder,
                    element = el,
                )
            }
        }
    }

    combine(
        accountFlow,
        organizationFlow,
        collectionFlow,
        folderFlow,
        readOnlyAccountFlow,
    ) { account, organization, collection, folder, readOnlyAccount ->
        val ownership = CreateRequest.Ownership2(
            accountId = account.value,
            folder = folder.value,
            organizationId = organization.value,
            collectionIds = collection.value,
        )
        val ui = AddStateOwnership(
            account = account.element?.copy(readOnly = readOnly || readOnlyAccount),
            organization = organization.element.takeIf { account.value != null },
            collection = collection.element.takeIf { account.value != null },
            folder = folder.element,
            onClick = onClick {
                val route = registerRouteResultReceiver(
                    route = ownershipConfirmationRoute(
                        title = translate(Res.string.save_to),
                        readOnly = readOnly,
                        ownership = ownership,
                        readOnlyAccount = readOnlyAccount,
                    ),
                ) { result ->
                    if (result is OrganizationConfirmationResult.Confirm) {
                        onChange(result)
                    }
                }
                val intent = NavigationIntent.NavigateToRoute(route)
                navigate(intent)
            },
        )
        transform(ownership, ui)
    }
}

/**
 * The "Save to" dialog of an item. A [readOnly] ownership
 * only lets the user change the folder.
 * [readOnlyAccount] can pin just the account of a new item.
 */
fun ownershipConfirmationRoute(
    title: String,
    readOnly: Boolean,
    ownership: CreateRequest.Ownership2,
    readOnlyAccount: Boolean = readOnly,
): OrganizationConfirmationRoute {
    val flags = if (readOnly) {
        OrganizationConfirmationRoute.Args.RO_ACCOUNT or
                OrganizationConfirmationRoute.Args.RO_ORGANIZATION or
                OrganizationConfirmationRoute.Args.RO_COLLECTION
    } else if (readOnlyAccount) {
        OrganizationConfirmationRoute.Args.RO_ACCOUNT
    } else {
        0
    }
    return OrganizationConfirmationRoute(
        args = OrganizationConfirmationRoute.Args(
            decor = OrganizationConfirmationRoute.Args.Decor(
                title = title,
                icon = Icons.Outlined.AccountBox,
            ),
            flags = flags,
            accountId = ownership.accountId,
            organizationId = ownership.organizationId,
            folderId = ownership.folder,
            collectionsIds = ownership.collectionIds,
        ),
    )
}
