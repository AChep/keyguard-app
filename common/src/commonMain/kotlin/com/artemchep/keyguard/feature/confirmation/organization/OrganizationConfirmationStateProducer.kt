package com.artemchep.keyguard.feature.confirmation.organization

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import arrow.core.andThen
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.displayName
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.common.util.contains
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedTitle
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.AccentColors
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.isDark
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun organizationConfirmationState(
    args: OrganizationConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<OrganizationConfirmationResult>,
): OrganizationConfirmationState = with(localDI().direct) {
    organizationConfirmationState(
        args = args,
        transmitter = transmitter,
        getProfiles = instance(),
        getOrganizations = instance(),
        getCollections = instance(),
        getFolders = instance(),
        windowCoroutineScope = instance(),
    )
}

@LeParcelize
@kotlinx.serialization.Serializable
data class FooBar(
    val accountId: String? = null,
    val organizationId: String? = null,
    val collectionsIds: Set<String> = emptySet(),
    val folderType: FolderInfoType = FolderInfoType.None,
    val folderId: String? = null,
) : LeParcelable

private fun FooBar.withAccountId(accountId: String?): FooBar {
    if (this.accountId == accountId) return this
    return FooBar(
        accountId = accountId,
    )
}

private fun FooBar.withOrganizationId(organizationId: String?): FooBar {
    if (this.organizationId == organizationId) return this
    return FooBar(
        accountId = this.accountId,
        organizationId = organizationId,
        folderType = this.folderType,
        folderId = this.folderId,
    )
}

private fun FooBar.withCollectionIdSet(ids: Set<String>): FooBar {
    if (this.collectionsIds == ids) return this
    return FooBar(
        accountId = this.accountId,
        organizationId = this.organizationId,
        collectionsIds = ids,
        folderType = this.folderType,
        folderId = this.folderId,
    )
}

private fun FooBar.withFolderId(folder: FolderVariant.FolderInfo): FooBar {
    val folderType = folder.type()
    val folderId = (folder as? FolderVariant.FolderInfo.Id)?.id
    if (
        this.folderType == folderType &&
        this.folderId == folderId
    ) {
        return this
    }
    return FooBar(
        accountId = this.accountId,
        organizationId = this.organizationId,
        collectionsIds = this.collectionsIds,
        folderType = folderType,
        folderId = folderId,
    )
}

private data class AccountVariant(
    val accountId: String,
    val name: String,
    val text: String,
    val enabled: Boolean,
    val accentColors: AccentColors,
) {
    val key = accountId
}

private data class OrganizationVariant(
    val accountId: String,
    val organizationId: String? = null,
    val name: String,
    val enabled: Boolean,
) {
    val key = "$accountId|$organizationId"
}

private data class CollectionVariant(
    val accountId: String,
    val organizationId: String? = null,
    val collectionId: String,
    val name: String,
    val readOnly: Boolean,
    val enabled: Boolean,
) {
    val key = "$accountId|$organizationId|$collectionId"
}

private data class FolderVariant(
    val accountId: String,
    val folder: FolderInfo,
    val name: String,
    val enabled: Boolean,
) {
    sealed interface FolderInfo {
        val key: String

        data object None : FolderInfo {
            override val key: String = "none"
        }

        data object New : FolderInfo {
            override val key: String = "new"
        }

        data class Id(
            val id: String,
        ) : FolderInfo {
            override val key: String = "id:$id"
        }

        fun type() = when (this) {
            is None -> FolderInfoType.None
            is New -> FolderInfoType.New
            is Id -> FolderInfoType.Id
        }

        fun folderId() = when (this) {
            is None -> null
            is New -> null
            is Id -> id
        }
    }

    val key = "$accountId|${folder.key}"
}

@Composable
fun organizationConfirmationState(
    args: OrganizationConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<OrganizationConfirmationResult>,
    getProfiles: GetProfiles,
    getOrganizations: GetOrganizations,
    getCollections: GetCollections,
    getFolders: GetFolders,
    windowCoroutineScope: WindowCoroutineScope,
): OrganizationConfirmationState = produceScreenState(
    key = "organization_confirmation",
    initial = OrganizationConfirmationState(),
    args = arrayOf(
        getFolders,
        windowCoroutineScope,
    ),
) {
    val readOnlyAccount = OrganizationConfirmationRoute.Args.RO_ACCOUNT in args.flags
    val readOnlyOrganization = OrganizationConfirmationRoute.Args.RO_ORGANIZATION in args.flags
    val readOnlyFolder = OrganizationConfirmationRoute.Args.RO_FOLDER in args.flags
    val readOnlyCollections = OrganizationConfirmationRoute.Args.RO_COLLECTION in args.flags

    val hideOrganization = OrganizationConfirmationRoute.Args.HIDE_ORGANIZATION in args.flags
    val hideFolder = OrganizationConfirmationRoute.Args.HIDE_FOLDER in args.flags
    val hideCollections = OrganizationConfirmationRoute.Args.HIDE_COLLECTION in args.flags

    val premiumAccount = OrganizationConfirmationRoute.Args.PREMIUM_ACCOUNT in args.flags

    val accountsFlow = getProfiles()
        .map { profiles ->
            profiles
                .map { profile ->
                    val enabled = profile.accountId() !in args.blacklistedAccountIds &&
                            (!premiumAccount || profile.premium)
                    AccountVariant(
                        accountId = profile.accountId(),
                        name = profile.displayName,
                        text = profile.accountHost,
                        enabled = enabled,
                        accentColors = profile.accentColor,
                    )
                }
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)
    val organizationsFlow = combine(
        getOrganizations(),
        accountsFlow,
    ) { organizations, accounts ->
        organizations
            .sortedWith(StringComparatorIgnoreCase { it.name })
            .map { organization ->
                val enabled = organization.id !in args.blacklistedOrganizationIds
                OrganizationVariant(
                    accountId = organization.accountId,
                    organizationId = organization.id,
                    name = organization.name,
                    enabled = enabled,
                )
            } + accounts
            .map { account ->
                val enabled = null !in args.blacklistedOrganizationIds &&
                        account.enabled
                OrganizationVariant(
                    accountId = account.accountId,
                    organizationId = null,
                    name = translate(Res.string.organization_none),
                    enabled = enabled,
                )
            }
    }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)
    val collectionsFlow = getCollections()
        .map { collections ->
            collections
                .sortedWith(StringComparatorIgnoreCase { it.name })
                .map { collection ->
                    val enabled = collection.id !in args.blacklistedCollectionIds &&
                            !collection.readOnly
                    CollectionVariant(
                        accountId = collection.accountId,
                        organizationId = collection.organizationId,
                        collectionId = collection.id,
                        name = collection.name,
                        readOnly = collection.readOnly,
                        enabled = enabled,
                    )
                }
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)
    val foldersFlow = combine(
        getFolders(),
        accountsFlow,
    ) { folders, accounts ->
        folders
            .sortedWith(StringComparatorIgnoreCase { it.name })
            .map { folder ->
                val enabled = folder.id !in args.blacklistedFolderIds
                FolderVariant(
                    accountId = folder.accountId,
                    folder = FolderVariant.FolderInfo.Id(folder.id),
                    name = folder.name,
                    enabled = enabled,
                )
            } + accounts
            .flatMap { account ->
                val items = mutableListOf<FolderVariant>()
                // No folder item
                run {
                    val enabled = null !in args.blacklistedFolderIds &&
                            account.enabled
                    items += FolderVariant(
                        accountId = account.accountId,
                        folder = FolderVariant.FolderInfo.None,
                        name = translate(Res.string.folder_none),
                        enabled = enabled,
                    )
                }
                // New folder item
                run {
                    val enabled = account.enabled
                    items += FolderVariant(
                        accountId = account.accountId,
                        folder = FolderVariant.FolderInfo.New,
                        name = translate(Res.string.folder_new),
                        enabled = enabled,
                    )
                }
                items
            }
    }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)

    val folderNameSink = mutablePersistedFlow("folder_name") {
        val folderName = (args.folderId as? FolderInfo.New)?.name
        folderName.orEmpty()
    }
    val folderNameState = mutableComposeState(folderNameSink)
    val folderValidatedFlow = folderNameSink
        .validatedTitle(this)
        .shareInScreenScope()

    val selectionSink = mutablePersistedFlow<FooBar>("selection") {
        val folderType = args.folderId.type()
        val folderId = (args.folderId as? FolderInfo.Id)?.id
        FooBar(
            accountId = args.accountId,
            organizationId = args.organizationId,
            collectionsIds = args.collectionsIds,
            folderType = folderType,
            folderId = folderId,
        )
    }
    val selectionFlow = combine(
        selectionSink,
        accountsFlow,
        organizationsFlow,
        collectionsFlow,
        foldersFlow,
    ) { selection, accounts, organizations, collections, folders ->
        var s = selection

        //
        // Accounts
        //

        val filteredAccounts = accounts
            .filter { it.enabled }
        // De-select non-existent account
        if (filteredAccounts.none { it.accountId == s.accountId }) {
            s = s.withAccountId(null)
        }
        // Pre-select the only available account
        if (filteredAccounts.size == 1 && s.accountId == null) {
            val accountId = filteredAccounts.first().accountId
            s = s.withAccountId(accountId)
        }

        //
        // Organizations
        //

        val filteredOrganizations = organizations
            .filter { it.accountId == s.accountId && it.enabled }
        // De-select non-existent organization
        if (filteredOrganizations.none { it.organizationId == s.organizationId }) {
            s = s.withOrganizationId(null)
        }
        // Pre-select the only available organization
        if (filteredOrganizations.size == 1 && s.organizationId == null) {
            val organizationId = filteredOrganizations.first().organizationId
            s = s.withOrganizationId(organizationId)
        }

        //
        // Collections
        //

        val filteredCollections = collections
            .filter {
                it.enabled &&
                        it.accountId == s.accountId &&
                        it.organizationId == s.organizationId
            }
        val filteredCollectionIds = filteredCollections
            .map { it.collectionId }
            .toSet()
        // De-select non-existent collections
        s = s.withCollectionIdSet(
            ids = filteredCollectionIds
                .intersect(s.collectionsIds),
        )
        // Pre-select the only available collection
        if (filteredCollectionIds.size == 1 && s.collectionsIds.isEmpty()) {
            s = s.withCollectionIdSet(filteredCollectionIds)
        }

        //
        // Folders
        //

        val filteredFolders = folders
            .filter { it.accountId == s.accountId && it.enabled }
        // De-select non-existent folder
        if (s.folderType == FolderInfoType.Id) {
            val hasSelectedFolder = filteredFolders
                .any { variant ->
                    when (val f = variant.folder) {
                        is FolderVariant.FolderInfo.Id -> f.id == s.folderId
                        else -> false
                    }
                }
            if (!hasSelectedFolder) {
                val newVariant = FolderVariant.FolderInfo.None
                s = s.withFolderId(newVariant)
            }
        }
        // Pre-select the only available folder
        if (filteredFolders.size == 1 && s.folderId == null) {
            val folderId = filteredFolders.first().folder
            s = s.withFolderId(folderId)
        }

        s
    }
        .distinctUntilChanged()
        .onEach {
            selectionSink.value = it
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)

    fun onClickAccount(accountId: String?) = selectionSink.update {
        it.withAccountId(accountId)
    }

    fun onClickOrganization(organizationId: String?) = selectionSink.update {
        it.withOrganizationId(organizationId)
    }

    fun onClickCollection(collectionId: String) = selectionSink.update {
        val existed = collectionId in it.collectionsIds
        val ids = if (existed) {
            it.collectionsIds - collectionId
        } else {
            it.collectionsIds + collectionId
        }
        it.withCollectionIdSet(ids)
    }

    fun onClickFolder(folder: FolderVariant.FolderInfo) = selectionSink.update {
        it.withFolderId(folder)
    }

    val itemAccountsFlow = combine(
        accountsFlow,
        selectionFlow
            .map { it.accountId }
            .distinctUntilChanged(),
    ) { accounts, selectedAccountId ->
        val items = accounts
            .map { account ->
                val selected = account.accountId == selectedAccountId
                val onClick = if (
                    account.enabled &&
                    !readOnlyAccount
                ) {
                    ::onClickAccount.partially1(account.accountId)
                } else {
                    null
                }
                OrganizationConfirmationState.Content.Item(
                    key = account.accountId,
                    title = account.name,
                    text = account.text,
                    selected = selected,
                    onClick = onClick,
                    icon = {
                        val accentColor = if (MaterialTheme.colorScheme.isDark) {
                            account.accentColors.dark
                        } else {
                            account.accentColors.light
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(accentColor, CircleShape),
                        )
                    },
                )
            }
        OrganizationConfirmationState.Content.Section(
            items = items,
        )
    }
    val itemOrganizationsFlow = if (hideOrganization) {
        // When we hide the organization UI we just display
        // a section as null.
        flowOf(null)
    } else combine(
        organizationsFlow,
        selectionFlow
            .map { it.organizationId to it.accountId }
            .distinctUntilChanged(),
    ) { organizations, (selectedOrganizationId, selectedAccountId) ->
        val items = organizations
            .asSequence()
            .filter { it.accountId == selectedAccountId }
            .map { organization ->
                val selected = organization.organizationId == selectedOrganizationId
                val onClick = if (
                    organization.enabled &&
                    !readOnlyOrganization
                ) {
                    ::onClickOrganization.partially1(organization.organizationId)
                } else {
                    null
                }
                OrganizationConfirmationState.Content.Item(
                    key = organization.key,
                    title = organization.name,
                    selected = selected,
                    onClick = onClick,
                )
            }
            .toList()
        OrganizationConfirmationState.Content.Section(
            items = items,
            text = translate(Res.string.destinationpicker_organization_ownership_note)
                .takeIf { selectedOrganizationId != null },
        )
    }
    val itemCollectionsFlow = if (hideCollections) {
        // When we hide the collection UI we just display
        // a section as null.
        flowOf(null)
    } else combine(
        collectionsFlow,
        selectionFlow
            .map {
                Triple(
                    it.collectionsIds,
                    it.organizationId,
                    it.accountId,
                )
            }
            .distinctUntilChanged(),
    ) { collections, (selectedCollectionIds, selectedOrganizationId, selectedAccountId) ->
        val items = collections
            .asSequence()
            .filter { it.accountId == selectedAccountId && it.organizationId == selectedOrganizationId }
            .map { collection ->
                val selected = collection.collectionId in selectedCollectionIds
                val onClick = if (
                    collection.enabled &&
                    !readOnlyCollections
                ) {
                    ::onClickCollection.partially1(collection.collectionId)
                } else {
                    null
                }
                OrganizationConfirmationState.Content.Item(
                    key = collection.key,
                    title = collection.name,
                    icon = Icons.Outlined.Lock
                        .takeIf { collection.readOnly }
                        ?.let {
                            // create icon composable
                            icon(it)
                        },
                    selected = selected,
                    onClick = onClick,
                )
            }
            .toList()
        OrganizationConfirmationState.Content.Section(
            items = items,
        )
    }

    data class Ty(
        val folderType: FolderInfoType,
        val folderId: String?,
        val accountId: String?,
    )

    val itemFoldersFlow = if (hideFolder) {
        // When we hide the collection UI we just display
        // a section as null.
        flowOf(null)
    } else combine(
        foldersFlow,
        selectionFlow
            .map {
                Ty(
                    folderType = it.folderType,
                    folderId = it.folderId,
                    accountId = it.accountId,
                )
            }
            .distinctUntilChanged(),
    ) { folders, (selectedFolderType, selectedFolderId, selectedAccountId) ->
        val items = folders
            .asSequence()
            .filter { it.accountId == selectedAccountId }
            .map { folder ->
                val selected = folder.folder.type() == selectedFolderType &&
                        folder.folder.folderId() == selectedFolderId
                val onClick = if (
                    folder.enabled &&
                    !readOnlyFolder
                ) {
                    ::onClickFolder.partially1(folder.folder)
                } else {
                    null
                }
                val icon = when (folder.folder) {
                    is FolderVariant.FolderInfo.None -> Icons.Outlined.FolderOff
                    is FolderVariant.FolderInfo.New -> Icons.Outlined.Add
                    else -> null
                }
                OrganizationConfirmationState.Content.Item(
                    key = folder.key,
                    icon = icon
                        ?.let {
                            // create icon composable
                            icon(it)
                        },
                    title = folder.name,
                    selected = selected,
                    onClick = onClick,
                )
            }
            .toList()
        OrganizationConfirmationState.Content.Section(
            items = items,
        )
    }

    val contentFlow = combine(
        itemAccountsFlow,
        itemOrganizationsFlow,
        itemCollectionsFlow,
        itemFoldersFlow,
        // folder name
        combine(
            selectionFlow
                .map { it.folderType }
                .distinctUntilChanged(),
            folderValidatedFlow,
        ) { folderType, folderNameValidated ->
            when (folderType) {
                FolderInfoType.New -> TextFieldModel2(
                    state = folderNameState,
                    text = folderNameValidated.model,
                    error = (folderNameValidated as? Validated.Failure)?.error,
                    onChange = folderNameState::value::set,
                )

                else -> null
            }
        },
    ) { accounts, organizations, collections, folders, folderNew ->
        OrganizationConfirmationState.Content(
            accounts = accounts,
            organizations = organizations,
            collections = collections,
            folders = folders,
            folderNew = folderNew,
        )
    }

    combine(
        contentFlow,
        combine(
            selectionFlow,
            folderValidatedFlow,
        ) { selection, folderNameValidated ->
            val onConfirm = if (
            // Account must be not empty
                selection.accountId != null &&
                // If you save to an organization, you must pick at least one
                // collection... otherwise the collection ids must be empty.
                (selection.organizationId != null) == selection.collectionsIds.isNotEmpty() &&
                (selection.folderType != FolderInfoType.New || folderNameValidated is Validated.Success)
            ) {
                val folder = when (selection.folderType) {
                    FolderInfoType.None -> FolderInfo.None
                    FolderInfoType.New -> FolderInfo.New(name = folderNameValidated.model)
                    FolderInfoType.Id -> {
                        val id = selection.folderId
                            ?: return@combine null // must not be null
                        FolderInfo.Id(id)
                    }
                }
                val result = OrganizationConfirmationResult.Confirm(
                    accountId = selection.accountId,
                    organizationId = selection.organizationId,
                    collectionsIds = selection.collectionsIds,
                    folderId = folder,
                )
                // lambda
                transmitter
                    .partially1(result)
                    .andThen {
                        navigatePopSelf()
                    }
            } else {
                null
            }
            onConfirm
        },
    ) { content, onConfirm ->
        OrganizationConfirmationState(
            content = Loadable.Ok(content),
            onDeny = {
                transmitter(OrganizationConfirmationResult.Deny)
                navigatePopSelf()
            },
            onConfirm = onConfirm,
        )
    }
}
