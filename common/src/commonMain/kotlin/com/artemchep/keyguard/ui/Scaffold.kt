package com.artemchep.keyguard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.pullrefresh.PullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.platform.leDisplayCutout
import com.artemchep.keyguard.platform.leIme
import com.artemchep.keyguard.platform.leSystemBars
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.animation.animateFloatStateOneWayAsState
import com.artemchep.keyguard.ui.scrollbar.ColumnScrollbar
import com.artemchep.keyguard.ui.scrollbar.LazyColumnScrollbar
import com.artemchep.keyguard.ui.selection.SelectionBar
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.theme.Dimen
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.LocalDimens
import com.artemchep.keyguard.ui.theme.LocalExpressive
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.log10

val screenMaxWidth = 768.dp
val screenMaxWidthCompact = 480.dp

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun ScaffoldLazyColumn(
    modifier: Modifier = Modifier,
    topAppBarScrollBehavior: TopAppBarScrollBehavior,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionState: State<FabState?> = rememberUpdatedState(newValue = null),
    floatingActionButton: @Composable FabScope.() -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    pullRefreshState: PullRefreshState? = null,
    expressive: Boolean = LocalExpressive.current,
    containerColor: Color = LocalSurfaceColor.current,
    provideContentUserScrollEnabled: () -> Boolean = { true },
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = scaffoldContentWindowInsets,
    overlay: @Composable OverlayScope.() -> Unit = {},
    listVerticalArrangement: Arrangement.Vertical = Arrangement.Top,
    listState: LazyListState = rememberLazyListState(),
    listContent: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val translationYState = remember(density, pullRefreshState) {
        derivedStateOf {
            val progress = run {
                val p = pullRefreshState?.progress ?: 0f
                log10(p.coerceAtLeast(0f) * 10 + 1)
            }
            progress * density.density * 64
        }
    }
    val translationYAnimatedState = animateFloatStateOneWayAsState(translationYState)

    ProvideScaffoldLocalValues(
        expressive = expressive,
    ) {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            floatingActionButton = {
                val expandedState = topAppBarScrollBehavior.rememberFabExpanded()
                val scope = remember(
                    floatingActionState,
                    expandedState,
                ) {
                    MutableFabScope(
                        state = floatingActionState,
                        expanded = expandedState,
                    )
                }
                floatingActionButton(scope)
            },
            floatingActionButtonPosition = floatingActionButtonPosition,
            containerColor = containerColor,
            contentColor = contentColor,
            contentWindowInsets = contentWindowInsets,
        ) { contentPadding ->
            val contentPaddingWithFab = calculatePaddingWithFab(
                contentPadding = contentPadding,
                fabState = floatingActionState,
            )
            val contentPaddingState = rememberUpdatedState(contentPaddingWithFab)
            LazyColumnScrollbar(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(contentWindowInsets),
                contentPadding = contentPadding,
                listState = listState,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = translationYAnimatedState.value
                        },
                    verticalArrangement = listVerticalArrangement,
                    contentPadding = contentPaddingWithFab,
                    state = listState,
                    userScrollEnabled = provideContentUserScrollEnabled(),
                ) {
                    listContent()
                }
            }
            val overlayScope = remember(contentPaddingState) {
                MutableOverlayScope(
                    contentPadding = contentPaddingState,
                )
            }
            overlay(overlayScope)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScaffoldColumn(
    modifier: Modifier = Modifier,
    topAppBarScrollBehavior: TopAppBarScrollBehavior,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionState: State<FabState?> = rememberUpdatedState(newValue = null),
    floatingActionButton: @Composable FabScope.() -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    expressive: Boolean = LocalExpressive.current,
    containerColor: Color = LocalSurfaceColor.current,
    provideContentUserScrollEnabled: () -> Boolean = { true },
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = scaffoldContentWindowInsets,
    overlay: @Composable OverlayScope.() -> Unit = {},
    columnModifier: Modifier = Modifier,
    columnVerticalArrangement: Arrangement.Vertical = Arrangement.Top,
    columnHorizontalAlignment: Alignment.Horizontal = Alignment.Start,
    columnScrollState: ScrollState = rememberScrollState(),
    columnContent: @Composable ColumnScope.() -> Unit,
) {
    ProvideScaffoldLocalValues(
        expressive = expressive,
    ) {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            floatingActionButton = {
                val expandedState = topAppBarScrollBehavior.rememberFabExpanded()
                val scope = remember(
                    floatingActionState,
                    expandedState,
                ) {
                    MutableFabScope(
                        state = floatingActionState,
                        expanded = expandedState,
                    )
                }
                floatingActionButton(scope)
            },
            floatingActionButtonPosition = floatingActionButtonPosition,
            containerColor = containerColor,
            contentColor = contentColor,
            contentWindowInsets = contentWindowInsets,
        ) { contentPadding ->
            val contentPaddingWithFab = calculatePaddingWithFab(
                contentPadding = contentPadding,
                fabState = floatingActionState,
            )
            val contentPaddingState = rememberUpdatedState(contentPaddingWithFab)
            ColumnScrollbar(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(contentWindowInsets),
                state = columnScrollState,
                contentPadding = contentPadding,
            ) {
                Box(
                    modifier = columnModifier
                        .fillMaxSize()
                        .verticalScroll(columnScrollState, enabled = provideContentUserScrollEnabled())
                        .padding(contentPaddingWithFab),
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = screenMaxWidth),
                        verticalArrangement = columnVerticalArrangement,
                        horizontalAlignment = columnHorizontalAlignment,
                    ) {
                        columnContent()
                    }
                }
            }
            val overlayScope = remember(contentPaddingState) {
                MutableOverlayScope(
                    contentPadding = contentPaddingState,
                )
            }
            overlay(overlayScope)
        }
    }
}

@Composable
fun ProvideScaffoldLocalValues(
    expressive: Boolean,
    content: @Composable () -> Unit,
) {
    val expressive = expressive && GlobalExpressive.current
    val dimens = calculateDimens(
        expressive = expressive,
    )
    CompositionLocalProvider(
        LocalDimens provides dimens,
        LocalExpressive provides expressive,
        content = content,
    )
}

@Composable
private fun calculateDimens(
    expressive: Boolean = LocalExpressive.current,
): Dimen {
    val dimens = remember(expressive) {
        val contentPadding: Dp
        if (expressive) {
            contentPadding = 16.dp
        } else {
            contentPadding = 8.dp
        }
        Dimen.normal().copy(
            contentPadding = contentPadding,
        )
    }
    return dimens
}

@Composable
private fun calculatePaddingWithFab(
    contentPadding: PaddingValues,
    fabState: State<FabState?>,
) = run {
    val dimens = LocalDimens.current
    val screenPaddingTop = dimens.contentPadding
    val screenPaddingBottom = dimens.contentPadding
    val fabHeight = 56.dp
    val fabPadding = fabHeight + 16.dp * 2
    val extraPadding = if (fabState.value != null) {
        PaddingValues(
            top = screenPaddingTop,
            bottom = fabPadding,
        )
    } else {
        PaddingValues(
            top = screenPaddingTop,
            bottom = screenPaddingBottom,
        )
    }
    contentPadding.plus(extraPadding)
}

@Immutable
data class FabState(
    val onClick: (() -> Unit)?,
    val model: Any?,
)

@Stable
interface FabScope {
    val state: State<FabState?>

    val expanded: State<Boolean>
}

private class MutableFabScope(
    override val state: State<FabState?>,
    override val expanded: State<Boolean>,
) : FabScope

@Composable
fun FabScope.DefaultFab(
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = contentColorFor(color),
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    val latestFabState = state.value
    val latestOnClick by rememberUpdatedState(latestFabState?.onClick)
    ExpandedIfNotEmpty(
        valueOrNull = latestFabState,
        enter = fadeIn() + scaleIn(),
        exit = scaleOut() + fadeOut(),
    ) { fabState ->
        val enabled = fabState.onClick != null
        val containerColorTarget =
            if (enabled) {
                color
            } else {
                color
                    .combineAlpha(DisabledEmphasisAlpha)
                    .compositeOver(MaterialTheme.colorScheme.surfaceVariant)
            }
        val containerColor by animateColorAsState(containerColorTarget)
        val contentColorTarget =
            if (enabled) {
                contentColor
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
                    .combineAlpha(DisabledEmphasisAlpha)
            }
        val contentColor by animateColorAsState(contentColorTarget)
        ExtendedFloatingActionButton(
            expanded = expanded.value && enabled,
            icon = icon,
            text = text,
            containerColor = containerColor,
            contentColor = contentColor,
            onClick = {
                latestOnClick?.invoke()
            },
        )
    }
}

@Composable
fun FabScope.SmallFab(
    icon: @Composable () -> Unit,
    onClick: (() -> Unit)?,
) {
    val latestFabState = state.value
    val latestOnClick by rememberUpdatedState(onClick)
    ExpandedIfNotEmpty(
        valueOrNull = latestFabState,
        enter = fadeIn() + scaleIn(),
        exit = scaleOut() + fadeOut(),
    ) { fabState ->
        val enabled = fabState.onClick != null
        val containerColorTarget =
            if (enabled) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
                    .combineAlpha(DisabledEmphasisAlpha)
                    .compositeOver(MaterialTheme.colorScheme.surfaceVariant)
            }
        val containerColor by animateColorAsState(containerColorTarget)
        val contentColorTarget =
            if (enabled) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
                    .combineAlpha(DisabledEmphasisAlpha)
            }
        val contentColor by animateColorAsState(contentColorTarget)
        SmallFloatingActionButton(
            containerColor = containerColor,
            contentColor = contentColor,
            onClick = {
                latestOnClick?.invoke()
            },
        ) {
            icon()
        }
    }
}

@Composable
fun DefaultSelection(
    state: Selection?,
) {
    ExpandedIfNotEmpty(
        valueOrNull = state,
    ) { selection ->
        SelectionBar(
            title = {
                val text = stringResource(Res.string.selection_n_selected, selection.count)
                Text(
                    text = text,
                    maxLines = 2,
                )
            },
            trailing = {
                val updatedOnSelectAll by rememberUpdatedState(selection.onSelectAll)
                IconButton(
                    enabled = updatedOnSelectAll != null,
                    onClick = {
                        updatedOnSelectAll?.invoke()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SelectAll,
                        contentDescription = null,
                    )
                }

                OptionsButton(
                    actions = selection.actions,
                )
            },
            onClear = selection.onClear,
        )
    }
}

@Stable
interface OverlayScope {
    val contentPadding: State<PaddingValues>
}

private class MutableOverlayScope(
    override val contentPadding: State<PaddingValues>,
) : OverlayScope

@Composable
fun OverlayScope.DefaultProgressBar(
    modifier: Modifier = Modifier,
    visible: Boolean,
) {
    val topPadding = Dimens.contentPadding
    val contentPadding = contentPadding
    val finalPadding = remember(topPadding, contentPadding) {
        derivedStateOf {
            val p = PaddingValues(top = topPadding)
            contentPadding.value - p
        }
    }
    AnimatedVisibility(
        modifier = modifier
            .padding(finalPadding.value),
        visible = visible,
        enter = fadeIn() + expandIn(
            initialSize = {
                IntSize(it.width, 0)
            },
        ),
        exit = shrinkOut(
            targetSize = {
                IntSize(it.width, 0)
            },
        ) + fadeOut(),
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth(),
        )
    }
}

@Immutable
data class Selection(
    val count: Int,
    val actions: ImmutableList<ContextItem>,
    val onSelectAll: (() -> Unit)? = null,
    val onClear: (() -> Unit)? = null,
)

val scaffoldContentWindowInsets
    @Composable
    get() = WindowInsets.leSystemBars
        .union(WindowInsets.leDisplayCutout)
        .union(WindowInsets.leIme)
