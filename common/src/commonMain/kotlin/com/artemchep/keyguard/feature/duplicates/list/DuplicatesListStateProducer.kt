package com.artemchep.keyguard.feature.duplicates.list

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSecretDuplicateGroup
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.canDelete
import com.artemchep.keyguard.common.model.canEdit
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.CipherDuplicatesCheck
import com.artemchep.keyguard.common.usecase.CipherToolbox
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.SelectableItemStateRaw
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.createElevatedAccessDialogIntent
import com.artemchep.keyguard.feature.duplicates.DuplicatesRoute
import com.artemchep.keyguard.feature.generator.history.mapLatestScoped
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRoute
import com.artemchep.keyguard.feature.home.vault.screen.toVaultListItem
import com.artemchep.keyguard.feature.home.vault.screen.verify
import com.artemchep.keyguard.feature.home.vault.util.cipherChangeNameAction
import com.artemchep.keyguard.feature.home.vault.util.cipherChangePasswordAction
import com.artemchep.keyguard.feature.home.vault.util.cipherCopyToAction
import com.artemchep.keyguard.feature.home.vault.util.cipherDeleteAction
import com.artemchep.keyguard.feature.home.vault.util.cipherDisableConfirmAccessAction
import com.artemchep.keyguard.feature.home.vault.util.cipherEditAction
import com.artemchep.keyguard.feature.home.vault.util.cipherEnableConfirmAccessAction
import com.artemchep.keyguard.feature.home.vault.util.cipherMergeInto
import com.artemchep.keyguard.feature.home.vault.util.cipherMergeIntoAction
import com.artemchep.keyguard.feature.home.vault.util.cipherMoveToFolderAction
import com.artemchep.keyguard.feature.home.vault.util.cipherRestoreAction
import com.artemchep.keyguard.feature.home.vault.util.cipherTrashAction
import com.artemchep.keyguard.feature.home.vault.util.cipherViewPasswordHistoryAction
import com.artemchep.keyguard.feature.home.vault.util.cipherWatchtowerAlerts
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.icons.KeyguardFavourite
import com.artemchep.keyguard.ui.icons.KeyguardFavouriteOutline
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.selection.SelectionHandle
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private data class ConfigMapper(
    val concealFields: Boolean,
    val appIcons: Boolean,
    val websiteIcons: Boolean,
)

private data class SelectionData(
    val ids: Set<String>,
    val group: String,
)

@Composable
fun produceDuplicatesListState(
    args: DuplicatesRoute.Args,
) = with(localDI().direct) {
    produceDuplicatesListState(
        directDI = this,
        args = args,
        clipboardService = instance(),
        getTotpCode = instance(),
        getConcealFields = instance(),
        getAppIcons = instance(),
        getWebsiteIcons = instance(),
        getOrganizations = instance(),
        getCollections = instance(),
        getCiphers = instance(),
        getProfiles = instance(),
        getCanWrite = instance(),
        cipherToolbox = instance(),
        cipherDuplicatesCheck = instance(),
    )
}

@Composable
fun produceDuplicatesListState(
    directDI: DirectDI,
    args: DuplicatesRoute.Args,
    clipboardService: ClipboardService,
    getTotpCode: GetTotpCode,
    getConcealFields: GetConcealFields,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    getOrganizations: GetOrganizations,
    getCollections: GetCollections,
    getCiphers: GetCiphers,
    getProfiles: GetProfiles,
    getCanWrite: GetCanWrite,
    cipherToolbox: CipherToolbox,
    cipherDuplicatesCheck: CipherDuplicatesCheck,
): Loadable<DuplicatesListState> = produceScreenState(
    key = "duplicates_list",
    initial = Loadable.Loading,
    args = arrayOf(
        getOrganizations,
        getCiphers,
        cipherDuplicatesCheck,
    ),
) {
    val copy = copy(clipboardService)
    val sensitivitySink = mutablePersistedFlow("sensitivity") {
        CipherDuplicatesCheck.Sensitivity.NORMAL
    }
    val itemSink = mutablePersistedFlow("item") { "" }
    val selectionHandle = selectionHandle("selection")
    val selectionGroupSink = mutablePersistedFlow("selection_group_id") {
        ""
    }
    val selectionFlow = combine(
        selectionGroupSink,
        selectionHandle.idsFlow,
    ) { group, ids ->
        SelectionData(
            group = group,
            ids = ids,
        )
    }

    fun onClickCipher(
        cipher: DSecret,
    ) {
        val route = VaultViewRoute(
            itemId = cipher.id,
            accountId = cipher.accountId,
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(DuplicatesRoute.ROUTER_NAME),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        navigate(intent)
    }

    fun onLongClickCipher(
        group: DSecretDuplicateGroup,
        cipher: DSecret,
    ) {
        val canToggle = selectionGroupSink.value == group.id ||
                selectionHandle.idsFlow.value.isEmpty()
        if (canToggle) {
            selectionGroupSink.value = group.id
            selectionHandle.toggleSelection(cipher.id)
        }
    }

    val configFlow = combine(
        getConcealFields(),
        getAppIcons(),
        getWebsiteIcons(),
    ) { concealFields, appIcons, websiteIcons ->
        ConfigMapper(
            concealFields = concealFields,
            appIcons = appIcons,
            websiteIcons = websiteIcons,
        )
    }.distinctUntilChanged()

    val organizationsByIdFlow = getOrganizations()
        .map { organizations ->
            organizations
                .associateBy { it.id }
        }

    val ciphersComparator = Comparator<DSecret> { a, b ->
        var r = a.name.compareTo(b.name, ignoreCase = true)
        if (r == 0) r = a.id.compareTo(b.id)
        r
    }
    val ciphersRawFlow = filterHiddenProfiles(
        getProfiles = getProfiles,
        getCiphers = getCiphers,
        filter = args.filter,
    )
    val ciphersFlow = ciphersRawFlow
        .map { ciphers ->
            ciphers
                .filter { it.deletedDate == null }
                .run {
                    val filter = args.filter
                    if (filter != null) {
                        val predicate = filter.prepare(directDI, ciphers)
                        filter(predicate)
                    } else {
                        this
                    }
                }
        }
        .shareInScreenScope()
    val groupsFlow = ciphersFlow
        .combine(sensitivitySink) { ciphers, sensitivity ->
            val groups =
                cipherDuplicatesCheck.invoke(ciphers, sensitivity)
            groups
                .map { group ->
                    val groupedCiphers = group.ciphers
                        .sortedWith(ciphersComparator)
                    group.copy(ciphers = groupedCiphers)
                }
                .sortedByDescending { it.accuracy }
        }
    val itemsFlow = combine(
        groupsFlow,
        organizationsByIdFlow,
        configFlow,
    ) { groups, organizationsById, cfg -> Triple(groups, organizationsById, cfg) }
        .mapLatestScoped { (groups, organizationsById, cfg) ->
            val totalItemsCount = groups.sumOf { it.ciphers.size }
            groups
                .flatMapIndexed { index: Int, group ->
                    val groupedItems = group
                        .ciphers
                        .map { cipher ->
                            val selectableFlow = selectionFlow
                                .map { data ->
                                    val matchesGroupId = data.group == group.id
                                    SelectableItemStateRaw(
                                        selecting = matchesGroupId && data.ids.isNotEmpty(),
                                        selected = matchesGroupId && cipher.id in data.ids,
                                        canSelect = matchesGroupId || data.ids.isEmpty(),
                                    )
                                }
                                .distinctUntilChanged()
                                .map { raw ->
                                    val toggle = ::onLongClickCipher
                                        .partially1(group)
                                        .partially1(cipher)
                                    val onClick = toggle.takeIf { raw.selecting }
                                    val onLongClick = toggle
                                        .takeIf { !raw.selecting && raw.canSelect }
                                    SelectableItemState(
                                        selecting = raw.selecting,
                                        selected = raw.selected,
                                        can = raw.canSelect,
                                        onClick = onClick,
                                        onLongClick = onLongClick,
                                    )
                                }
                            val openedStateFlow = itemSink
                                .map {
                                    val isOpened = it == cipher.id
                                    VaultItem2.Item.OpenedState(isOpened)
                                }
                            val sharing = SharingStarted.WhileSubscribed(1000L)
                            val localStateFlow = combine(
                                selectableFlow,
                                openedStateFlow,
                            ) { selectableState, openedState ->
                                VaultItem2.Item.LocalState(
                                    openedState,
                                    selectableState,
                                )
                            }.persistingStateIn(this, sharing)
                            val item = cipher.toVaultListItem(
                                groupId = group.id,
                                copy = copy,
                                translator = this@produceScreenState,
                                getTotpCode = getTotpCode,
                                concealFields = cfg.concealFields,
                                appIcons = cfg.appIcons,
                                websiteIcons = cfg.websiteIcons,
                                organizationsById = organizationsById,
                                localStateFlow = localStateFlow,
                                onClick = {
                                    VaultItem2.Item.Action.Go(
                                        onClick = ::onClickCipher
                                            .partially1(cipher),
                                    )
                                },
                                onClickAttachment = {
                                    null
                                },
                                onClickPasskey = {
                                    null
                                },
                            )
                            item
                        }
                    val allItems = mutableListOf<VaultItem2>()
                    if (index > 0) {
                        allItems += VaultItem2.Section(
                            id = group.id,
                        )
                    }
                    allItems += groupedItems
                    allItems += VaultItem2.Button(
                        id = "merge." + group.id,
                        title = translate(Res.strings.ciphers_action_merge_title),
                        leading = icon(Icons.Outlined.Merge, Icons.Outlined.Add),
                        onClick = {
                            val ciphers = groupedItems
                                .map { it.source }
                            cipherMergeInto(
                                cipherMerge = cipherToolbox.cipherMerge,
                                ciphers = ciphers,
                            )
                        },
                    )
                    allItems
                }
        }

    val selectionStateFlow = createCipherSelectionFlow(
        selectionHandle = selectionHandle,
        ciphersFlow = ciphersFlow,
        collectionsFlow = getCollections(),
        canWriteFlow = getCanWrite(),
        toolbox = cipherToolbox,
    )
        .persistingStateIn(this, SharingStarted.WhileSubscribed())

    itemsFlow
        .combine(sensitivitySink) { items, sensitivity ->
            val state = DuplicatesListState(
                onSelected = { key ->
                    itemSink.value = key.orEmpty()
                },
                items = items,
                sensitivity = sensitivity,
                sensitivities = CipherDuplicatesCheck.Sensitivity
                    .entries
                    .map { s ->
                        FlatItemAction(
                            title = translate(s.title),
                            onClick = {
                                sensitivitySink.value = s
                            },
                        )
                    },
                selectionStateFlow = selectionStateFlow,
            )
            Loadable.Ok(state)
        }
}

fun RememberStateFlowScope.createCipherSelectionFlow(
    selectionHandle: SelectionHandle,
    ciphersFlow: Flow<List<DSecret>>,
    collectionsFlow: Flow<List<DCollection>>,
    canWriteFlow: Flow<Boolean>,
    //
    toolbox: CipherToolbox,
) = combine(
    ciphersFlow,
    selectionHandle.idsFlow,
    collectionsFlow,
    canWriteFlow,
) { ciphers, selectedCipherIds, collections, canWrite ->
    if (selectedCipherIds.isEmpty()) {
        return@combine null
    }

    val selectedCiphers = ciphers
        .filter { it.id in selectedCipherIds }
    val selectedCiphersByAccount = selectedCiphers
        .groupBy { it.accountId }

    val selectedCiphersAllLogins = selectedCiphers.all { it.type == DSecret.Type.Login }
    val selectedCiphersAllSameType = kotlin.run {
        if (selectedCiphersAllLogins) {
            return@run true
        }

        val type = selectedCiphers.firstOrNull()?.type
        selectedCiphers.all { it.type == type }
    }

    val selectedCollections = selectedCiphers
        .asSequence()
        .flatMap { it.collectionIds }
        .distinct()
        .mapNotNull { collectionId ->
            collections
                .firstOrNull { it.id == collectionId }
        }
    // Find ciphers that have some limitations
    val hasCanNotEditCiphers = selectedCiphers.any { !it.canEdit() }
    val hasCanNotDeleteCiphers = selectedCiphers.any { !it.canDelete() }
    val hasCanNotWriteCiphers = selectedCollections.any { it.readOnly }
    val hasRepromptCiphers = selectedCiphers.any { it.reprompt }

    val canEdit = canWrite && !hasCanNotEditCiphers && !hasCanNotWriteCiphers
    val canDelete = canWrite && !hasCanNotDeleteCiphers && !hasCanNotWriteCiphers

    val verify: ((() -> Unit) -> Unit)? = if (hasRepromptCiphers) {
        // lambda
        { block ->
            val intent = createElevatedAccessDialogIntent {
                block()
            }
            navigate(intent)
        }
    } else {
        null
    }

    val actions = mutableListOf<FlatItemAction>()
    // If any of the ciphers can be favourite-d, then we
    // show an action to do it!
    if (canEdit && selectedCiphers.any { !it.favorite }) {
        actions += FlatItemAction(
            icon = Icons.Outlined.KeyguardFavourite,
            title = translate(Res.strings.ciphers_action_add_to_favorites_title),
            onClick = {
                val filteredCipherIds = selectedCiphers
                    .asSequence()
                    .filter { !it.favorite }
                    .map { it.id }
                    .toSet()
                toolbox
                    .favouriteCipherById(
                        filteredCipherIds,
                        true,
                    )
                    .effectMap {
                        val message = ToastMessage(
                            title = "Add to favourites",
                        )
                        message(message)
                    }
                    .launchIn(appScope)
            },
        )
    }
    // If any of the ciphers can be un-favourited, then we
    // show an action to do it!
    if (canEdit && selectedCiphers.any { it.favorite }) {
        actions += FlatItemAction(
            icon = Icons.Outlined.KeyguardFavouriteOutline,
            title = translate(Res.strings.ciphers_action_remove_from_favorites_title),
            onClick = {
                val filteredCipherIds = selectedCiphers
                    .asSequence()
                    .filter { it.favorite }
                    .map { it.id }
                    .toSet()
                toolbox
                    .favouriteCipherById(
                        filteredCipherIds,
                        false,
                    )
                    .effectMap {
                        val message = ToastMessage(
                            title = "Removed from favourites",
                        )
                        message(message)
                    }
                    .launchIn(appScope)
            },
        )
    }

    if (canEdit && selectedCiphers.any { !it.reprompt }) {
        actions += cipherEnableConfirmAccessAction(
            rePromptCipherById = toolbox.rePromptCipherById,
            ciphers = selectedCiphers,
        )
    }
    if (canEdit && selectedCiphers.any { it.reprompt }) {
        actions += cipherDisableConfirmAccessAction(
            rePromptCipherById = toolbox.rePromptCipherById,
            ciphers = selectedCiphers,
        ).verify(verify)
    }

    if (canEdit && selectedCiphers.size == 1) {
        val cipher = selectedCiphers.first()
        actions += cipherEditAction(
            cipher = cipher,
        ).verify(verify)
    }

    if (selectedCiphers.size == 1 && selectedCiphersAllLogins) {
        val cipher = selectedCiphers.first()
        if (!cipher.login?.passwordHistory.isNullOrEmpty()) {
            actions += cipherViewPasswordHistoryAction(
                cipher = cipher,
            ).verify(verify)
        }
    }

    if (canEdit) {
        actions += cipherChangeNameAction(
            changeCipherNameById = toolbox.changeCipherNameById,
            ciphers = selectedCiphers,
        )
    }

    if (canEdit && selectedCiphersAllLogins) {
        actions += cipherChangePasswordAction(
            changeCipherPasswordById = toolbox.changeCipherPasswordById,
            ciphers = selectedCiphers,
        ).verify(verify)
    }

    if (canWrite && selectedCiphersAllSameType && selectedCiphers.size > 1) {
        actions += cipherMergeIntoAction(
            cipherMerge = toolbox.cipherMerge,
            ciphers = selectedCiphers,
        ).verify(verify)
    }

    if (canWrite) {
        actions += cipherCopyToAction(
            copyCipherById = toolbox.copyCipherById,
            ciphers = selectedCiphers,
        )
    }

    if (canEdit && selectedCiphersByAccount.size == 1) {
        val selectedAccount = selectedCiphersByAccount.entries.first()
        actions += cipherMoveToFolderAction(
            moveCipherToFolderById = toolbox.moveCipherToFolderById,
            accountId = AccountId(selectedAccount.key),
            ciphers = selectedAccount.value,
        )
    }

    actions += cipherWatchtowerAlerts(
        patchWatchtowerAlertCipher = toolbox.patchWatchtowerAlertCipher,
        ciphers = selectedCiphers,
    )

    if (canDelete && selectedCiphers.any { it.deletedDate != null }) {
        actions += cipherRestoreAction(
            restoreCipherById = toolbox.restoreCipherById,
            ciphers = selectedCiphers,
        )
    }
    if (canDelete && selectedCiphers.any { it.deletedDate == null && it.service.remote != null }) {
        actions += cipherTrashAction(
            trashCipherById = toolbox.trashCipherById,
            ciphers = selectedCiphers,
        )
    }

    if (canDelete && selectedCiphers.all { it.deletedDate != null || it.service.remote == null }) {
        actions += cipherDeleteAction(
            removeCipherById = toolbox.removeCipherById,
            ciphers = selectedCiphers,
        )
    }

    Selection(
        count = selectedCiphers.size,
        actions = actions.toPersistentList(),
        onClear = selectionHandle::clearSelection,
    )
}
