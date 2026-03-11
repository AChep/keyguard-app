package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import com.artemchep.keyguard.common.io.nullable
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.keepass.KeePassLoginRoute
import com.artemchep.keyguard.feature.generator.history.mapLatestScoped
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.IndexedModel
import com.artemchep.keyguard.feature.home.vault.screen.buildSearchTokens
import com.artemchep.keyguard.feature.home.vault.screen.search
import com.artemchep.keyguard.feature.home.vault.screen.toVaultListItem
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.feature.navigation.keyboard.interceptKeyEvents
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.navigation.state.copy
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.SEARCH_DEBOUNCE
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.copy_card_number
import com.artemchep.keyguard.res.copy_cvv_code
import com.artemchep.keyguard.res.copy_email
import com.artemchep.keyguard.res.copy_otp_code
import com.artemchep.keyguard.res.copy_password
import com.artemchep.keyguard.res.copy_phone_number
import com.artemchep.keyguard.res.copy_username
import com.artemchep.keyguard.res.copy_value
import com.artemchep.keyguard.res.uri_action_launch_browser_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
internal fun quickSearchScreenState(): QuickSearchState = with(localDI().direct) {
    quickSearchScreenState(
        highlightBackgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        highlightContentColor = MaterialTheme.colorScheme.onSurface,
        getAccounts = instance(),
        getProfiles = instance(),
        getCiphers = instance(),
        getOrganizations = instance(),
        getTotpCode = instance(),
        getConcealFields = instance(),
        getAppIcons = instance(),
        getWebsiteIcons = instance(),
        clipboardService = instance(),
    )
}

@Composable
internal fun quickSearchScreenState(
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getCiphers: GetCiphers,
    getOrganizations: GetOrganizations,
    getTotpCode: GetTotpCode,
    getConcealFields: GetConcealFields,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
    clipboardService: ClipboardService,
): QuickSearchState = produceScreenState(
    key = QUICK_SEARCH_SCREEN_STATE_KEY,
    initial = QuickSearchState(),
    args = arrayOf(
        getAccounts,
        getCiphers,
        getTotpCode,
        clipboardService,
    ),
) {
    val copy = copy(clipboardService)
    val querySink = mutablePersistedFlow("query") { "" }
    val queryState = mutableComposeState(querySink)
    val queryFocusSink = EventFlow<Unit>()
    val selectedItemIdSink = mutablePersistedFlow<String?>("selected_item_id") { null }
    val selectedActionIndexSink = mutablePersistedFlow<Int?>("selected_action_index") { null }

    fun clearField() {
        queryState.value = ""
    }

    fun focusField() {
        queryFocusSink.emit(Unit)
    }

    interceptBackPress(
        interceptorFlow = querySink
            .map { it.isNotEmpty() }
            .distinctUntilChanged()
            .map { enabled ->
                if (enabled) {
                    ::clearField
                } else {
                    null
                }
            },
    )

    interceptKeyEvents(
        KeyShortcut(
            key = Key.F,
            isCtrlPressed = true,
            isAltPressed = true,
        ) to flowOf(true)
            .map { enabled ->
                if (enabled) {
                    {
                        clearField()
                        focusField()
                    }
                } else {
                    null
                }
            },
    )

    val hiddenCiphersFlow = filterHiddenProfiles(
        getProfiles = getProfiles,
        getCiphers = getCiphers,
        filter = null,
    )
    val queryTrimmedFlow = querySink.map { it to it.trim() }
    val queryIndexedFlow = queryTrimmedFlow
        .debounce(SEARCH_DEBOUNCE)
        .map { (_, queryTrimmed) ->
            queryTrimmed
                .takeIf { it.isNotEmpty() }
                ?.let(IndexedText::invoke)
        }
    val organizationsByIdFlow = getOrganizations()
        .map { organizations ->
            organizations.associateBy(DOrganization::id)
        }
    val configFlow = combine(
        getConcealFields(),
        getAppIcons(),
        getWebsiteIcons(),
    ) { concealFields, appIcons, websiteIcons ->
        QuickSearchConfig(
            concealFields = concealFields,
            appIcons = appIcons,
            websiteIcons = websiteIcons,
        )
    }.distinctUntilChanged()
    val itemLocalState = MutableStateFlow(
        VaultItem2.Item.LocalState(
            openedState = VaultItem2.Item.OpenedState(false),
            selectableItemState = SelectableItemState(
                selecting = false,
                selected = false,
                can = false,
                onClick = null,
                onLongClick = null,
            ),
        ),
    )

    val itemsFlow = combine(
        hiddenCiphersFlow,
        organizationsByIdFlow,
        configFlow,
    ) { secrets, organizationsById, config ->
        Triple(secrets, organizationsById, config)
    }
        .mapLatestScoped { (secrets, organizationsById, config) ->
            secrets
                .filter { it.deletedDate == null }
                .map { secret ->
                    val item = secret.toVaultListItem(
                        copy = copy,
                        translator = this@produceScreenState,
                        getTotpCode = getTotpCode,
                        appIcons = config.appIcons,
                        websiteIcons = config.websiteIcons,
                        concealFields = config.concealFields,
                        organizationsById = organizationsById,
                        localStateFlow = itemLocalState,
                        onClick = {
                            VaultItem2.Item.Action.None
                        },
                        onClickAttachment = {
                            null
                        },
                        onClickPasskey = {
                            null
                        },
                        onClickPassword = {
                            null
                        },
                    )
                    val indexed = buildSearchTokens(secret)
                    IndexedModel(
                        model = item,
                        indexedText = IndexedText(item.title.text),
                        indexedOther = indexed,
                    )
                }
                .sortedWith(quickSearchComparator)
                .toList()
        }
        .flowOn(Dispatchers.Default)
        .shareIn(this, SharingStarted.WhileSubscribed(5000L), replay = 1)

    val filteredItemsFlow = itemsFlow
        .combine(queryIndexedFlow) { items, query ->
            items to query
        }
        .mapLatest { (items, query) ->
            if (query == null) {
                items.map(IndexedModel<VaultItem2.Item>::model)
            } else {
                items.search(
                    query = query,
                    highlightBackgroundColor = highlightBackgroundColor,
                    highlightContentColor = highlightContentColor,
                )
            }
        }
        .flowOn(Dispatchers.Default)
    val filteredItemsNullableFlow = filteredItemsFlow
        .nullable()
        .persistingStateIn(this, SharingStarted.WhileSubscribed(), null)

    combine(
        filteredItemsNullableFlow,
        queryTrimmedFlow,
        getAccounts()
            .map { it.isNotEmpty() }
            .distinctUntilChanged(),
        selectedItemIdSink,
        selectedActionIndexSink,
    ) { items, (query, _), hasAccounts, selectedItemId, selectedActionIndex ->
        val content = quickSearchContent(
            results = items,
            hasAccounts = hasAccounts,
            onAddAccount = quickSearchAddAccountAction(hasAccounts),
        )
        val queryField = if (hasAccounts) {
            TextFieldModel2(
                state = queryState,
                text = query,
                focusFlow = queryFocusSink,
                onChange = queryState::value::set,
            )
        } else {
            TextFieldModel2(
                mutableStateOf(""),
            )
        }
        val reconciledSelectedItemId = reconcileSelectedResultId(
            currentSelectedItemId = selectedItemId,
            results = content.results,
        )
        val selection = quickSearchSelectionState(
            selectedItemId = selectedItemId,
            reconciledSelectedItemId = reconciledSelectedItemId,
            selectedActionIndex = selectedActionIndex,
            results = content.results,
        )

        QuickSearchState(
            query = queryField,
            results = content.results
                .map { item ->
                    QuickSearchResultItem(
                        item = item,
                        selected = item.id == reconciledSelectedItemId,
                    )
                },
            emptyState = content.emptyState,
            selectedItem = selection.selectedResult,
            selectedItemId = reconciledSelectedItemId,
            defaultAction = selection.defaultAction,
            selectedActionIndex = selection.selectedActionIndex,
            actions = selection.actions,
            onSelectItem = { itemId ->
                if (selectedItemIdSink.value != itemId) {
                    selectedItemIdSink.value = itemId
                }
                selectedActionIndexSink.value = null
            },
            onMoveSelection = { direction ->
                val nextSelectedItemId = moveSelectedResultId(
                    currentSelectedItemId = reconciledSelectedItemId,
                    results = content.results,
                    direction = direction,
                )
                if (nextSelectedItemId != reconciledSelectedItemId) {
                    selectedItemIdSink.value = nextSelectedItemId
                    selectedActionIndexSink.value = null
                }
            },
            onMoveActionSelection = { direction ->
                selectedActionIndexSink.value = moveSelectedActionIndex(
                    currentSelectedActionIndex = selection.selectedActionIndex,
                    actions = selection.actionTypes,
                    direction = direction,
                )
            },
            onClearActionSelection = {
                selectedActionIndexSink.value = null
            },
        )
    }
}

private data class QuickSearchConfig(
    val concealFields: Boolean,
    val appIcons: Boolean,
    val websiteIcons: Boolean,
)

private data class QuickSearchSelectionState(
    val selectedResult: QuickSearchResultItem?,
    val defaultAction: QuickSearchActionType?,
    val selectedActionIndex: Int?,
    val actions: List<QuickSearchAction>,
    val actionTypes: List<QuickSearchActionType>,
)

private fun RememberStateFlowScope.quickSearchAddAccountAction(
    hasAccounts: Boolean,
): ((AccountType) -> Unit)? = if (hasAccounts) {
    null
} else {
    { type ->
        val routeMain = when (type) {
            AccountType.BITWARDEN -> BitwardenLoginRoute()
            AccountType.KEEPASS -> KeePassLoginRoute
        }
        val route = registerRouteResultReceiver(routeMain) {
            navigate(NavigationIntent.Pop)
        }
        navigate(NavigationIntent.NavigateToRoute(route))
    }
}

private suspend fun TranslatorScope.quickSearchSelectionState(
    selectedItemId: String?,
    reconciledSelectedItemId: String?,
    selectedActionIndex: Int?,
    results: List<VaultItem2.Item>,
): QuickSearchSelectionState {
    val selectedItem = results.firstOrNull { it.id == reconciledSelectedItemId }
    val selectedResult = selectedItem?.let { item ->
        QuickSearchResultItem(
            item = item,
            selected = true,
        )
    }
    val actionTypes = selectedItem
        ?.let { quickSearchActionTypes(it.source) }
        .orEmpty()
    val reconciledSelectedActionIndex = selectedActionIndex
        ?.takeIf { reconciledSelectedItemId == selectedItemId }
        ?.takeIf { it in actionTypes.indices }
    val defaultAction = selectedItem
        ?.let { defaultQuickSearchActionType(it.source) }
    val actions = actionTypes
        .mapIndexed { index, actionType ->
            QuickSearchAction(
                type = actionType,
                title = quickSearchActionTitle(
                    actionType = actionType,
                    item = selectedItem,
                ),
                shortcut = quickSearchShortcut(actionType),
                selected = index == reconciledSelectedActionIndex,
            )
        }
    return QuickSearchSelectionState(
        selectedResult = selectedResult,
        defaultAction = defaultAction,
        selectedActionIndex = reconciledSelectedActionIndex,
        actions = actions,
        actionTypes = actionTypes,
    )
}

private val quickSearchComparator = Comparator<IndexedModel<VaultItem2.Item>> { a, b ->
    var result = 0
    if (result == 0) {
        result = -compareValues(a.model.favourite, b.model.favourite)
    }
    if (result == 0) {
        result = AlphabeticalSort.compare(a.model, b.model)
    }
    if (result == 0) {
        result = a.model.id.compareTo(b.model.id)
    }
    result
}

private suspend fun TranslatorScope.quickSearchActionTitle(
    actionType: QuickSearchActionType,
    item: VaultItem2.Item?,
): String = when (actionType) {
    QuickSearchActionType.CopyPrimary -> when (quickSearchPrimaryCopy(item!!.source)?.type) {
        com.artemchep.keyguard.common.usecase.CopyText.Type.USERNAME ->
            translate(Res.string.copy_username)

        com.artemchep.keyguard.common.usecase.CopyText.Type.CARD_NUMBER ->
            translate(Res.string.copy_card_number)

        com.artemchep.keyguard.common.usecase.CopyText.Type.EMAIL ->
            translate(Res.string.copy_email)

        com.artemchep.keyguard.common.usecase.CopyText.Type.PHONE_NUMBER ->
            translate(Res.string.copy_phone_number)

        else -> translate(Res.string.copy_value)
    }

    QuickSearchActionType.CopySecret -> when (quickSearchSecretCopy(item!!.source)?.type) {
        com.artemchep.keyguard.common.usecase.CopyText.Type.PASSWORD ->
            translate(Res.string.copy_password)

        com.artemchep.keyguard.common.usecase.CopyText.Type.CARD_CVV ->
            translate(Res.string.copy_cvv_code)

        else -> translate(Res.string.copy_value)
    }

    QuickSearchActionType.CopyOtp ->
        translate(Res.string.copy_otp_code)

    QuickSearchActionType.OpenInBrowser ->
        translate(Res.string.uri_action_launch_browser_title)
}
