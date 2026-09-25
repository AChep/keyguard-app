package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.resolveWebsiteIconUrl
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.scope.Scope

enum class QuickSearchHeadlessEmptyState {
    LOADING,
    IDLE,
    NO_ITEMS,
    ADD_ACCOUNT,
}

data class QuickSearchHeadlessItem(
    val id: String,
    val iconUrl: String?,
    /** Initials to render when [iconUrl] is `null` / fails to load. */
    val iconPlaceholder: String?,
    val token: TotpToken?,
)

/** A copy / open action available for the selected item. [type] is a
 * [QuickSearchActionType] name (e.g. "CopyPrimary"); [shortcut] is the macOS
 * key-combo hint (e.g. "⌘C"). */
data class QuickSearchHeadlessAction(
    val type: String,
    val title: String,
    val shortcut: String?,
    val selected: Boolean,
)

data class QuickSearchHeadlessDetail(
    val title: String,
    val primaryType: String?,
    val primaryValue: String?,
    val secretType: String?,
    val secretValue: String?,
    val hasOtp: Boolean,
    val launchUrl: String?,
)

data class QuickSearchHeadlessState(
    val query: String,
    val queryRevision: Int,
    val results: List<QuickSearchHeadlessItem>,
    val emptyState: QuickSearchHeadlessEmptyState,
    val selectedItemId: String?,
    val selectedActionIndex: Int?,
    val actions: List<QuickSearchHeadlessAction>,
    val selectedDetail: QuickSearchHeadlessDetail?,
) {
    companion object {
        val empty = QuickSearchHeadlessState(
            query = "",
            queryRevision = 0,
            results = emptyList(),
            emptyState = QuickSearchHeadlessEmptyState.LOADING,
            selectedItemId = null,
            selectedActionIndex = null,
            actions = emptyList(),
            selectedDetail = null,
        )
    }
}

class QuickSearchHeadlessController internal constructor(
    private val scope: CoroutineScope,
    private val getTotpCode: GetTotpCode,
    private val onOpenUrl: (String) -> Unit,
) {
    private val _state = MutableStateFlow(QuickSearchHeadlessState.empty)
    val state: Flow<QuickSearchHeadlessState> get() = _state.asStateFlow()

    private val _results = MutableStateFlow<List<VaultItem2>>(emptyList())

    val results: StateFlow<List<VaultItem2>> get() = _results.asStateFlow()

    private var latest: QuickSearchState? = null

    internal fun update(s: QuickSearchState) {
        latest = s
        _results.value = s.results.map { it.item }
        _state.value = s.toHeadless()
    }

    fun setQuery(text: String) {
        latest?.query?.onChange?.invoke(text)
    }

    fun clearQuery() {
        latest?.query?.onSetText?.invoke("")
    }

    fun moveSelection(direction: Int) {
        latest?.onMoveSelection?.invoke(direction)
    }

    fun moveActionSelection(direction: Int) {
        latest?.onMoveActionSelection?.invoke(direction)
    }

    fun selectItem(id: String) {
        latest?.onSelectItem?.invoke(id)
    }

    fun clearActionSelection() {
        latest?.onClearActionSelection?.invoke()
    }

    /** Performs the item's default action (the first available copy/open). */
    fun performDefaultAction() {
        val s = latest ?: return
        val item = s.selectedItem?.item ?: return
        val type = s.defaultAction ?: return
        perform(type, item)
    }

    /** Performs a specific action by its [QuickSearchActionType] name. */
    fun performAction(typeName: String) {
        val s = latest ?: return
        val item = s.selectedItem?.item ?: return
        val type = runCatching { QuickSearchActionType.valueOf(typeName) }.getOrNull() ?: return
        perform(type, item)
    }

    private fun perform(type: QuickSearchActionType, item: VaultItem2.Item) {
        when (val resolved = quickSearchResolvedAction(type, item)) {
            is QuickSearchResolvedAction.Copy ->
                item.copyText.copy(
                    text = resolved.value,
                    hidden = resolved.hidden,
                    type = resolved.type,
                )

            is QuickSearchResolvedAction.CopyOtp -> scope.launch {
                val code = getTotpCode(resolved.token)
                    .firstOrNull()
                    ?.getOrNull()
                    ?.code
                    ?: return@launch
                item.copyText.copy(
                    text = code,
                    hidden = false,
                    type = CopyText.Type.OTP,
                )
            }

            is QuickSearchResolvedAction.OpenInBrowser -> onOpenUrl(resolved.url)

            null -> Unit
        }
    }
}

private fun QuickSearchState.toHeadless(): QuickSearchHeadlessState = QuickSearchHeadlessState(
    query = query.text,
    queryRevision = query.textRevision,
    results = results.map { r ->
        QuickSearchHeadlessItem(
            id = r.item.id,
            iconUrl = r.item.icon.resolveWebsiteIconUrl(),
            iconPlaceholder = VaultItemIcon.TextIcon.short(r.item.title.text).text,
            token = r.item.token,
        )
    },
    emptyState = when (emptyState) {
        QuickSearchEmptyState.Loading -> QuickSearchHeadlessEmptyState.LOADING
        QuickSearchEmptyState.Idle -> QuickSearchHeadlessEmptyState.IDLE
        QuickSearchEmptyState.NoItems -> QuickSearchHeadlessEmptyState.NO_ITEMS
        is QuickSearchEmptyState.AddAccount -> QuickSearchHeadlessEmptyState.ADD_ACCOUNT
    },
    selectedItemId = selectedItemId,
    selectedActionIndex = selectedActionIndex,
    actions = actions.map { a ->
        QuickSearchHeadlessAction(
            type = a.type.name,
            title = a.title,
            shortcut = a.type.macShortcutText(),
            selected = a.selected,
        )
    },
    selectedDetail = selectedItem?.item?.source?.let { source ->
        val primary = quickSearchPrimaryCopy(source)
        val secret = quickSearchSecretCopy(source)
        QuickSearchHeadlessDetail(
            title = selectedItem.item.title.text,
            primaryType = primary?.type?.name,
            primaryValue = primary?.value,
            secretType = secret?.type?.name,
            secretValue = secret?.value,
            hasOtp = quickSearchOtpToken(source) != null,
            launchUrl = quickSearchLaunchUrl(source),
        )
    },
)

private fun QuickSearchActionType.macShortcutText(): String = when (this) {
    QuickSearchActionType.CopyPrimary -> "⌘C"
    QuickSearchActionType.CopySecret -> "⌘⇧C"
    QuickSearchActionType.CopyOtp -> "⌘⌥C"
    QuickSearchActionType.OpenInBrowser -> "⌘⇧F"
}

suspend fun RememberStateFlowScope.createQuickSearchHeadlessController(
    collectScope: CoroutineScope,
    sessionKoin: Scope,
    onOpenUrl: (String) -> Unit,
): QuickSearchHeadlessController {
    val controller = QuickSearchHeadlessController(
        scope = collectScope,
        getTotpCode = sessionKoin.get(),
        onOpenUrl = onOpenUrl,
    )
    val flow = quickSearchScreenStateProducer(
        highlightBackgroundColor = Color.Transparent,
        highlightContentColor = Color.Transparent,
        getAccounts = sessionKoin.get(),
        getProfiles = sessionKoin.get(),
        getCiphers = sessionKoin.get(),
        getOrganizations = sessionKoin.get(),
        getVaultSearchIndex = sessionKoin.get(),
        getVaultSearchQualifierCatalog = sessionKoin.get(),
        searchTraceSink = sessionKoin.get(),
        queryHighlighter = sessionKoin.get(),
        getTotpCode = sessionKoin.get(),
        getConcealFields = sessionKoin.get(),
        getAppIcons = sessionKoin.get(),
        getWebsiteIcons = sessionKoin.get(),
        clipboardService = sessionKoin.get(),
        bitwardenLoginRouteFactory = sessionKoin.get(),
    )
    flow.onEach { controller.update(it) }.launchIn(collectScope)
    return controller
}
