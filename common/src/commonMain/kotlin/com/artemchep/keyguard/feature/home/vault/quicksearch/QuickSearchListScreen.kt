package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.PromoView
import com.artemchep.keyguard.feature.home.vault.component.AccountListItemTextIcon
import com.artemchep.keyguard.feature.home.vault.component.AddAccountView
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.SmartBadge
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRoute
import com.artemchep.keyguard.feature.keyguard.setup.keyguardSpan
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationAnimation
import com.artemchep.keyguard.feature.navigation.NavigationAnimationType
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.transform
import com.artemchep.keyguard.feature.rememberPromoViewStatus
import com.artemchep.keyguard.feature.twopane.EmptyKeyguardBox
import com.artemchep.keyguard.platform.LocalAnimationFactor
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.PlainTextField
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.shortcut.toText
import com.artemchep.keyguard.ui.surface.ProvideSurfaceColor
import com.artemchep.keyguard.ui.surface.ReportSurfaceColor
import com.artemchep.keyguard.ui.surface.surfaceElevationColor
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.selectedContainer
import com.artemchep.keyguard.ui.util.VerticalDivider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
internal fun QuickSearchListScreen(
    activationRevision: Int,
    onDismissRequest: (() -> Unit)?,
) {
    val state = quickSearchScreenState()
    val selectedItem = state.selectedItem?.item
    val controller by rememberUpdatedState(LocalNavigationController.current)
    val directDI = localDI().direct
    val getTotpCode: GetTotpCode = remember(directDI) { directDI.instance() }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester2() }
    val performAction = remember(selectedItem, controller, getTotpCode, scope, focusRequester) {
        { actionType: QuickSearchActionType ->
            val item = selectedItem ?: return@remember
            performQuickSearchAction(
                actionType = actionType,
                item = item,
                controller = controller,
                getTotpCode = getTotpCode,
                scope = scope,
                onFinished = focusRequester::requestFocus,
            )
        }
    }
    val performShortcutAction = remember(
        selectedItem,
        controller,
        getTotpCode,
        scope,
        onDismissRequest,
    ) {
        { actionType: QuickSearchActionType ->
            val item = selectedItem ?: return@remember
            performQuickSearchAction(
                actionType = actionType,
                item = item,
                controller = controller,
                getTotpCode = getTotpCode,
                scope = scope,
                onFinished = {
                    onDismissRequest?.invoke()
                },
            )
        }
    }
    val performDefaultAction = remember(state.defaultAction, selectedItem, performAction) {
        {
            val actionType = state.defaultAction
                ?: return@remember
            if (selectedItem != null) {
                performAction(actionType)
            }
        }
    }
    val resultsListState = rememberLazyListState()

    LaunchedEffect(activationRevision) {
        delay(200L)
        focusRequester.requestFocus()
    }

    LaunchedEffect(state.selectedItemId, state.results.size) {
        val index = state.results.indexOfFirst { it.item.id == state.selectedItemId }
        if (index >= 0) {
            resultsListState.animateScrollToItem(index)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .onPreviewKeyEvent { event ->
                handleQuickSearchKeyEvent(
                    input = event.toQuickSearchKeyInput(),
                    state = state,
                    onPerformDefaultAction = performDefaultAction,
                    onPerformSelectedAction = performAction,
                    onPerformShortcutAction = performShortcutAction,
                )
            },
    ) {
        val quickQuery = state.query
        QuickSearchSearch(
            modifier = Modifier,
            text = quickQuery.state.value,
            placeholder = stringResource(Res.string.vault_main_search_placeholder),
            focusRequester = focusRequester,
            focusFlow = quickQuery.focusFlow,
            onTextChange = { value ->
                state.onClearActionSelection()
                quickQuery.onChange?.invoke(value)
            },
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline
                .combineAlpha(0.18f),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .weight(0.40f)
                    .fillMaxSize(),
            ) {
                when (val emptyState = state.emptyState) {
                    QuickSearchEmptyState.Idle -> {
                        LazyColumn(
                            state = resultsListState,
                            modifier = Modifier
                                .fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 8.dp,
                                bottom = 8.dp,
                            ),
                        ) {
                            itemsIndexed(
                                items = state.results,
                                key = { _, result -> result.item.id },
                            ) { _, result ->
                                QuickSearchResultRow(
                                    item = result.item,
                                    selected = result.selected,
                                    onClick = {
                                        state.onSelectItem(result.item.id)
                                        focusRequester.requestFocus()
                                    },
                                )
                            }
                        }
                    }

                    is QuickSearchEmptyState.AddAccount -> {
                        AddAccountView(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            onClick = emptyState.onAddAccount,
                        )
                    }

                    QuickSearchEmptyState.Loading -> {
                        QuickSearchLoadingPlaceholder(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                        )
                    }

                    QuickSearchEmptyState.NoItems -> {
                        QuickSearchListPlaceholder(
                            modifier = Modifier.fillMaxSize(),
                            text = stringResource(Res.string.items_empty_label),
                        )
                    }
                }
            }
            VerticalDivider()

            val color = kotlin.run {
                val elevation = 0.75f // hardcoded for nice UI
                surfaceElevationColor(elevation)
            }
            ProvideSurfaceColor(color) {
                ReportSurfaceColor()

                Box(
                    modifier = Modifier
                        .background(color)
                        .weight(0.6f)
                        .fillMaxSize(),
                ) {
                    QuickSearchDetailPane(
                        item = selectedItem,
                    )
                }
            }
        }
        QuickSearchActionStrip(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .fillMaxWidth(),
            actions = state.actions,
            onActionClick = performAction,
        )
    }
}

@Composable
private fun QuickSearchSearch(
    modifier: Modifier = Modifier,
    text: String,
    placeholder: String,
    focusRequester: FocusRequester2,
    focusFlow: Flow<Unit>?,
    playPromo: Boolean = false,
    onTextChange: ((String) -> Unit)?,
) {
    val promoState = rememberPromoViewStatus(
        playPromo = playPromo,
        ready = onTextChange != null,
    )

    val interactionSource = remember {
        MutableInteractionSource()
    }

    LaunchedEffect(focusFlow) {
        focusFlow
            ?: return@LaunchedEffect
        focusFlow.collect {
            focusRequester.requestFocus(showKeyboard = true)
        }
    }

    val isFocused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .size(24.dp),
        )
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        )
        Spacer(
            modifier = Modifier
                .size(16.dp),
        )

        val textStyle = TextStyle(
            fontSize = 20.sp,
        )
        val updatedOnChange by rememberUpdatedState(onTextChange)
        PlainTextField(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .focusRequester2(focusRequester)
                // When focused, clicking the Escape key should
                // clear the text field.
                .onKeyEvent { keyEvent ->
                    if (
                        keyEvent.key == Key.Escape &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        text.isNotEmpty()
                    ) {
                        updatedOnChange?.invoke("")
                        return@onKeyEvent true
                    }

                    false
                },
            interactionSource = interactionSource,
            value = text,
            textStyle = textStyle,
            placeholder = {
                PromoView(
                    state = promoState,
                    promo = {
                        val promo = remember {
                            keyguardSpan()
                        }
                        Text(
                            text = promo,
                            style = textStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            enabled = onTextChange != null,
            onValueChange = {
                updatedOnChange?.invoke(it)
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            singleLine = true,
        )
        Spacer(
            modifier = Modifier
                .size(8.dp),
        )
    }
}

@Composable
private fun QuickSearchListPlaceholder(
    modifier: Modifier = Modifier,
    text: String,
) {
    EmptySearchView(
        modifier = modifier,
        text = {
            Text(text = text)
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickSearchResultRow(
    item: VaultItem2.Item,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = when {
        selected -> MaterialTheme.colorScheme.selectedContainer
        else -> Color.Unspecified
    }
    // We explicitly do not support the expressive shape merging here,
    // because it looks ugly.
    FlatItemLayoutExpressive(
        modifier = Modifier
            .fillMaxWidth(),
        backgroundColor = backgroundColor,
        padding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 1.dp,
            bottom = 2.dp, // in Android notifications the margin is 3 dp
        ),
        content = {
            FlatItemTextContent(
                title = {
                    val title = item.title
                        .takeUnless { it.isEmpty() }
                    if (title != null) {
                        Text(
                            text = title,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Text(
                            text = stringResource(Res.string.empty_value),
                            color = LocalContentColor.current
                                .combineAlpha(DisabledEmphasisAlpha),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                },
                text = item.text
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        // composable
                        {
                            Text(
                                text = it,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = if (item.source.type == DSecret.Type.SecureNote) 4 else 2,
                            )
                        }
                    },
            )
        },
        leading = {
            AccountListItemTextIcon(
                modifier = Modifier,
                item = item,
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun QuickSearchLoadingPlaceholder(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(6) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
            ) {}
        }
    }
}

@Composable
private fun QuickSearchActionStrip(
    modifier: Modifier = Modifier,
    actions: List<QuickSearchAction>,
    onActionClick: (QuickSearchActionType) -> Unit,
) {
    if (actions.isEmpty()) {
        return
    }

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { action ->
            key(action.type) {
                SmartBadge(
                    modifier = Modifier,
                    title = action.title,
                    text = action.shortcut?.toText()?.text,
                    selected = action.selected,
                    onClick = {
                        onActionClick(action.type)
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickSearchDetailPane(
    item: VaultItem2.Item?,
) {
    if (item == null) {
        EmptyKeyguardBox(
            modifier = Modifier
                .fillMaxSize(),
        )
        return
    }

    val route = remember(
        item.accountId,
        item.id,
    ) {
        VaultViewRoute(
            itemId = item.id,
            accountId = item.accountId,
        )
    }
    val updatedAnimationScale by rememberUpdatedState(LocalAnimationFactor)
    AnimatedContent(
        modifier = Modifier
            .fillMaxSize(),
        targetState = route,
        transitionSpec = {
            val animationType = NavAnimation.DYNAMIC
            val transitionType = NavigationAnimationType.SWITCH

            NavigationAnimation.transform(
                scale = updatedAnimationScale,
                animationType = animationType,
                transitionType = transitionType,
            )
        },
        label = "",
    ) { r ->
        val id = r.accountId + "," + r.itemId
        NavigationNode(
            id = id,
            route = r,
        )
    }
}

internal fun handleQuickSearchKeyEvent(
    input: QuickSearchKeyInput,
    state: QuickSearchState,
    onPerformDefaultAction: () -> Unit,
    onPerformSelectedAction: (QuickSearchActionType) -> Unit,
    onPerformShortcutAction: (QuickSearchActionType) -> Unit,
): Boolean = quickSearchKeyEventAction(
    input = input,
    state = state,
)?.let { action ->
    when (action) {
        is QuickSearchKeyEventAction.MoveSelection -> {
            state.onMoveSelection(action.direction)
        }

        is QuickSearchKeyEventAction.MoveActionSelection -> {
            state.onMoveActionSelection(action.direction)
        }

        is QuickSearchKeyEventAction.PerformSelectedAction -> {
            onPerformSelectedAction(action.type)
        }

        is QuickSearchKeyEventAction.PerformShortcutAction -> {
            onPerformShortcutAction(action.type)
        }

        QuickSearchKeyEventAction.PerformDefaultAction -> {
            onPerformDefaultAction()
        }

        QuickSearchKeyEventAction.ClearQuery -> {
            state.onClearActionSelection()
            state.query.onChange?.invoke("")
        }
    }
    true
} ?: false

internal sealed interface QuickSearchResolvedAction {
    data class Copy(
        val value: String,
        val hidden: Boolean,
        val type: CopyText.Type,
    ) : QuickSearchResolvedAction

    data class CopyOtp(
        val token: TotpToken,
    ) : QuickSearchResolvedAction

    data class OpenInBrowser(
        val url: String,
    ) : QuickSearchResolvedAction
}

internal fun performQuickSearchAction(
    actionType: QuickSearchActionType,
    item: VaultItem2.Item,
    controller: NavigationController,
    getTotpCode: GetTotpCode,
    scope: kotlinx.coroutines.CoroutineScope,
    onFinished: () -> Unit,
) {
    when (val resolvedAction = quickSearchResolvedAction(actionType, item)) {
        is QuickSearchResolvedAction.Copy -> {
            item.copyText.copy(
                text = resolvedAction.value,
                hidden = resolvedAction.hidden,
                type = resolvedAction.type,
            )
            onFinished()
        }

        is QuickSearchResolvedAction.CopyOtp -> {
            scope.launch {
                val code = getTotpCode(resolvedAction.token)
                    .firstOrNull()
                    ?.code
                    ?: return@launch
                item.copyText.copy(
                    text = code,
                    hidden = false,
                    type = CopyText.Type.OTP,
                )
                onFinished()
            }
        }

        is QuickSearchResolvedAction.OpenInBrowser -> {
            controller.queue(NavigationIntent.NavigateToBrowser(resolvedAction.url))
            onFinished()
        }

        null -> Unit
    }
}

internal fun quickSearchResolvedAction(
    actionType: QuickSearchActionType,
    item: VaultItem2.Item,
): QuickSearchResolvedAction? = when (actionType) {
    QuickSearchActionType.CopyPrimary -> quickSearchPrimaryCopy(item.source)
        ?.let { copy ->
            QuickSearchResolvedAction.Copy(
                value = copy.value,
                hidden = false,
                type = copy.type,
            )
        }

    QuickSearchActionType.CopySecret -> quickSearchSecretCopy(item.source)
        ?.let { copy ->
            QuickSearchResolvedAction.Copy(
                value = copy.value,
                hidden = true,
                type = copy.type,
            )
        }

    QuickSearchActionType.CopyOtp -> quickSearchOtpToken(item.source)
        ?.let { token ->
            QuickSearchResolvedAction.CopyOtp(token.token)
        }

    QuickSearchActionType.OpenInBrowser -> quickSearchLaunchUrl(item.source)
        ?.let { url ->
            QuickSearchResolvedAction.OpenInBrowser(url)
        }
}
