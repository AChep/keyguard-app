package com.artemchep.keyguard.feature.generator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import arrow.core.partially1
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.auth.common.IntFieldModel
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.twopane.TwoPaneScreen
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.PasswordStrengthBadge
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.animation.animateContentHeight
import com.artemchep.keyguard.ui.collectIsInteractedWith
import com.artemchep.keyguard.ui.colorizePassword
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.minus
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.skeleton.SkeletonSection
import com.artemchep.keyguard.ui.skeleton.SkeletonSlider
import com.artemchep.keyguard.ui.surface.LocalBackgroundManager
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.horizontalPaddingHalf
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.SmallToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import com.artemchep.keyguard.ui.util.VerticalDivider
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun GeneratorScreen(
    args: GeneratorRoute.Args,
) {
    val loadableState = produceGeneratorState(
        mode = LocalAppMode.current,
        args = args,
    )

    val scrollBehavior = ToolbarBehavior.behavior()
    val sliderInteractionSource = remember {
        MutableInteractionSource()
    }
    TwoPaneScreen(
        header = { modifier ->
            SmallToolbar(
                modifier = modifier,
                containerColor = Color.Transparent,
                title = {
                    Text(
                        text = stringResource(Res.string.generator_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    loadableState.fold(
                        ifLoading = {
                        },
                        ifOk = { state ->
                            val updatedOnOpenHistory by rememberUpdatedState(state.onOpenHistory)
                            IconButton(
                                onClick = {
                                    updatedOnOpenHistory()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                )
                            }
                            val actions = state.options
                            OptionsButton(actions)
                        },
                    )
                },
            )

            SideEffect {
                if (scrollBehavior.state.heightOffsetLimit != 0f) {
                    scrollBehavior.state.heightOffsetLimit = 0f
                }
            }
        },
        detail = { modifier ->
            GeneratorPaneDetail(
                modifier = modifier,
                loadableState = loadableState,
                sliderInteractionSource = sliderInteractionSource,
            )
        },
    ) { modifier, tabletUi ->
        val stickyValueUi = maxHeight >= 480.dp
        GeneratorPaneMaster(
            modifier = modifier,
            loadableState = loadableState,
            tabletUi = tabletUi,
            stickyValueUi = stickyValueUi,
            scrollBehavior = scrollBehavior,
            sliderInteractionSource = sliderInteractionSource,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratorPaneDetail(
    modifier: Modifier = Modifier,
    loadableState: Loadable<GeneratorState>,
    sliderInteractionSource: MutableInteractionSource,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    ScaffoldColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
    ) {
        loadableState.fold(
            ifLoading = {
                SkeletonItem(
                    titleWidthFraction = 0.6f,
                    textWidthFraction = null,
                )
                SkeletonSection()
                SkeletonItem()
                SkeletonItem()
            },
            ifOk = { state ->
                GeneratorPaneDetailContent(
                    state = state,
                    sliderInteractionSource = sliderInteractionSource,
                )
            },
        )
    }
}

@Composable
private fun ColumnScope.GeneratorPaneDetailContent(
    state: GeneratorState,
    sliderInteractionSource: MutableInteractionSource,
) {
    GeneratorType(
        state = state,
    )

    GeneratorFilterItems(
        filterFlow = state.filterState,
        sliderInteractionSource = sliderInteractionSource,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratorPaneMaster(
    modifier: Modifier,
    loadableState: Loadable<GeneratorState>,
    tabletUi: Boolean,
    stickyValueUi: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    sliderInteractionSource: MutableInteractionSource,
) {
    val loadedState = loadableState.getOrNull()?.loadedState?.collectAsState()
    val loaded by remember(loadedState) {
        derivedStateOf {
            loadedState?.value?.loaded == true
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    ScaffoldColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            if (tabletUi) {
                return@ScaffoldColumn
            }

            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.generator_header_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    loadableState.fold(
                        ifLoading = {
                        },
                        ifOk = { state ->
                            val updatedOnOpenHistory by rememberUpdatedState(state.onOpenHistory)
                            IconButton(
                                onClick = {
                                    updatedOnOpenHistory()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                )
                            }
                            val actions = state.options
                            OptionsButton(actions)
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val valueStateOrNull = loadableState.getOrNull()?.valueState
            val valueState = remember(valueStateOrNull) {
                valueStateOrNull
                    ?: MutableStateFlow(null)
            }.collectAsState()

            val onClick by remember(valueState) {
                derivedStateOf { valueState.value?.onRefresh }
            }
            val fabState = FabState(
                onClick = onClick.takeIf { loaded },
                model = null,
            )
            rememberUpdatedState(fabState)
        },
        floatingActionButton = {
            DefaultFab(
                icon = {
                    Crossfade(
                        modifier = Modifier
                            .size(24.dp),
                        targetState = loaded,
                    ) {
                        if (it) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                            )
                        } else {
                            CircularProgressIndicator(
                                color = LocalContentColor.current,
                            )
                        }
                    }
                },
            ) {
                val valueState = loadableState.getOrNull()?.valueState?.collectAsState()
                val valueExists by remember(valueState) {
                    derivedStateOf {
                        !valueState?.value?.password.isNullOrEmpty()
                    }
                }
                Text(
                    modifier = Modifier
                        .animateContentSize(),
                    text = if (valueExists) {
                        stringResource(Res.string.generator_regenerate_button)
                    } else {
                        stringResource(Res.string.generator_generate_button)
                    },
                )
            }
        },
        overlay = {
            val strengthState = remember {
                mutableStateOf(false)
            }
            val passwordState = remember {
                mutableStateOf<String?>(null)
            }
            LaunchedEffect(loadableState) {
                val state = loadableState.getOrNull()
                    ?: kotlin.run {
                        strengthState.value = false
                        passwordState.value = null
                        return@LaunchedEffect
                    }
                state.valueState
                    .onEach { value ->
                        strengthState.value = value?.strength == true
                        passwordState.value = value?.password
                            ?.takeIf { it.isNotEmpty() }
                    }
                    .collect()
            }

            val density = rememberUpdatedState(LocalDensity.current)
            val valueState = remember {
                derivedStateOf {
                    val thresholdScroll = 86 * density.value.density
                    passwordState.value
                        ?.takeIf { scrollState.value > thresholdScroll }
                }
            }
            ExpandedIfNotEmpty(
                modifier = Modifier
                    .padding(
                        contentPadding.value.minus(
                            PaddingValues(
                                top = 8.dp,
                                bottom = 8.dp,
                            ),
                        ),
                    )
                    .fillMaxWidth(),
                valueOrNull = valueState.value.takeIf { stickyValueUi },
            ) { password ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LocalBackgroundManager.current.colorHighest)
                        .clickable {
                            // Scroll up to see the header
                            coroutineScope.launch {
                                scrollState.animateScrollTo(0)
                            }
                        }
                        .padding(
                            horizontal = 16.dp,
                            vertical = 8.dp,
                        ),
                ) {
                    AutoResizeText(
                        modifier = Modifier,
                        maxFontSize = 24f,
                        minFontSize = 13f,
                        maxLines = 2,
                        text = colorizePasswordOrEmpty(password),
                    )
                    Spacer(
                        modifier = Modifier
                            .height(4.dp),
                    )
                    ExpandedIfNotEmpty(
                        valueOrNull = password.takeIf { strengthState.value },
                    ) { pwd ->
                        PasswordStrengthBadge(
                            password = pwd,
                        )
                    }
                }
            }
        },
        columnScrollState = scrollState,
    ) {
        loadableState.fold(
            ifLoading = {
                SkeletonItem()
            },
            ifOk = { state ->
                GeneratorPaneMasterContent(
                    state = state,
                    tabletUi = tabletUi,
                    sliderInteractionSource = sliderInteractionSource,
                )
            },
        )
    }
}

@Composable
private fun ColumnScope.GeneratorPaneMasterContent(
    state: GeneratorState,
    tabletUi: Boolean,
    sliderInteractionSource: MutableInteractionSource,
) {
    if (!tabletUi) {
        GeneratorType(
            state = state,
        )
    }

    GeneratorValue(
        valueFlow = state.valueState,
        sliderInteractionSource = sliderInteractionSource,
    )

    GeneratorFilterTip(
        filterFlow = state.filterState,
    )

    if (!tabletUi) {
        GeneratorFilterItems(
            filterFlow = state.filterState,
            sliderInteractionSource = sliderInteractionSource,
        )
    }
}

@Composable
fun ColumnScope.GeneratorType(
    state: GeneratorState,
) {
    val type by state.typeState.collectAsState()
    FlatDropdown(
        content = {
            FlatItemTextContent(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier
                                .weight(1f, fill = false),
                            text = type.title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                        DropdownIcon()
                    }
                },
            )
        },
        dropdown = type.items,
    )
}

@Composable
fun ColumnScope.GeneratorValue(
    valueFlow: StateFlow<GeneratorState.Value?>,
    sliderInteractionSource: MutableInteractionSource,
) {
    val valueState = valueFlow.collectAsState()
    ExpandedIfNotEmpty(
        valueOrNull = valueState.value,
    ) { value ->
        val sliderInteractionSourceIsInteracted =
            sliderInteractionSource.collectIsInteractedWith()
        FlatDropdown(
            elevation = 1.dp,
            content = {
                ExpandedIfNotEmpty(
                    valueOrNull = value.title,
                ) { title ->
                    Text(
                        modifier = Modifier
                            .padding(bottom = 4.dp),
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Crossfade(
                    targetState = value.password,
                    modifier = Modifier
                        .animateContentHeight(),
                ) { password ->
                    AutoResizeText(
                        text = colorizePasswordOrEmpty(password),
                    )
                }
                Spacer(
                    modifier = Modifier
                        .height(4.dp),
                )

                val visible = value.strength
                ExpandedIfNotEmpty(
                    valueOrNull = value.password.takeIf { visible },
                ) { password ->
                    PasswordStrengthBadge(
                        password = password,
                    )
                }
            },
            trailing = {
                ExpandedIfNotEmptyForRow(
                    valueOrNull = Unit.takeIf { value.onCopy != null },
                ) {
                    val updatedOnCopy by rememberUpdatedState(value.onCopy)
                    IconButton(
                        enabled = value.onCopy != null,
                        onClick = {
                            updatedOnCopy?.invoke()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                        )
                    }
                }
            },
            dropdown = value.dropdown,
            actions = value.actions,
            enabled = !sliderInteractionSourceIsInteracted,
        )
    }
}

@Composable
fun colorizePasswordOrEmpty(
    password: String,
): AnnotatedString {
    return if (password.isEmpty()) {
        val text = stringResource(Res.string.empty_value)
        buildAnnotatedString {
            withStyle(
                style = SpanStyle(
                    color = LocalContentColor.current
                        .combineAlpha(DisabledEmphasisAlpha),
                ),
            ) {
                append(text)
            }
        }
    } else {
        colorizePassword(
            password = password,
            contentColor = LocalContentColor.current,
        )
    }
}

@Composable
private fun ColumnScope.GeneratorFilterTip(
    filterFlow: StateFlow<GeneratorState.Filter>,
) {
    val filterState = filterFlow.collectAsState()
    val tipState = remember(filterState) {
        derivedStateOf {
            filterState.value.tip
        }
    }
    ExpandedIfNotEmpty(
        valueOrNull = tipState.value,
    ) { tip ->
        Column(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Spacer(
                modifier = Modifier
                    .height(32.dp),
            )
            Icon(
                modifier = Modifier
                    .padding(horizontal = Dimens.horizontalPadding),
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
            )
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    modifier = Modifier
                        .weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    text = tip.text,
                    color = LocalContentColor.current
                        .combineAlpha(alpha = MediumEmphasisAlpha),
                )
                Spacer(modifier = Modifier.width(16.dp))
                if (tip.onLearnMore != null) {
                    TextButton(
                        onClick = {
                            tip.onLearnMore.invoke()
                        },
                    ) {
                        Text(
                            text = stringResource(Res.string.learn_more),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun ColumnScope.GeneratorFilterItems(
    filterFlow: StateFlow<GeneratorState.Filter>,
    sliderInteractionSource: MutableInteractionSource,
) {

    val filterState = filterFlow.collectAsState()
    val filter by filterState

    val showSection = filter.length != null || filter.items.isNotEmpty()
    ExpandedIfNotEmpty(
        Unit.takeIf { showSection },
    ) {
        Section(
            text = stringResource(Res.string.filter_header_title),
        )
    }

    ExpandedIfNotEmpty(
        filter.length,
    ) {
        Column {
            DecoratedSlider(
                filterLength = it,
                interactionSource = sliderInteractionSource,
            )
        }
    }

    Spacer(
        modifier = Modifier
            .height(16.dp),
    )

    val items = filter.items
    items.forEach { item ->
        key(item.key) {
            FilterItem(item)
        }
    }
}

@Composable
fun FilterItem(
    item: GeneratorState.Filter.Item,
) = when (item) {
    is GeneratorState.Filter.Item.Switch -> FilterSwitchItem(item)
    is GeneratorState.Filter.Item.Text -> FilterTextItem(item)
    is GeneratorState.Filter.Item.Enum -> FilterEnumItem(item)
    is GeneratorState.Filter.Item.Section -> FilterSectionItem(item)
}

@Composable
private fun FilterSwitchItem(
    item: GeneratorState.Filter.Item.Switch,
) {
    BoxWithConstraints {
        val compact = maxWidth <= 356.dp && item.counter != null
        val horizontal = maxWidth >= 296.dp

        val updatedItemState = rememberUpdatedState(newValue = item)
        val updatedCompactState = rememberUpdatedState(newValue = compact)
        val movableSwitchContent = remember(updatedItemState, updatedCompactState) {
            movableContentOf {
                FilterSwitchItemMainContent(
                    item = updatedItemState.value,
                    compact = updatedCompactState.value,
                )
            }
        }

        if (horizontal) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f),
                ) {
                    movableSwitchContent()
                }
                ExpandedIfNotEmptyForRow(
                    valueOrNull = item.counter,
                ) { counter ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VerticalDivider(
                            modifier = Modifier
                                .height(20.dp),
                        )
                        Spacer(
                            modifier = Modifier
                                .width(Dimens.horizontalPadding),
                        )
                        FilterSwitchItemCounter(
                            counter = counter,
                        )
                        Spacer(
                            modifier = Modifier
                                .width(Dimens.horizontalPadding),
                        )
                    }
                }
            }
        } else {
            Column {
                movableSwitchContent()
                ExpandedIfNotEmpty(
                    modifier = Modifier
                        .padding(
                            start = Dimens.horizontalPadding,
                            end = Dimens.horizontalPadding,
                            top = Dimens.horizontalPaddingHalf,
                            bottom = Dimens.horizontalPaddingHalf,
                        ),
                    valueOrNull = item.counter,
                ) { counter ->
                    FilterSwitchItemCounter(
                        counter = counter,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterSwitchItemMainContent(
    item: GeneratorState.Filter.Item.Switch,
    compact: Boolean,
) {
    FlatItem(
        leading = if (item.icon != null && !compact) {
            icon<RowScope>(item.icon)
        } else {
            null
        },
        title = {
            Text(
                text = item.title,
                maxLines = 2,
            )
        },
        text = if (item.text != null) {
            // composable
            {
                Text(
                    text = item.text,
                    maxLines = 1,
                )
            }
        } else {
            null
        },
        trailing = {
            Switch(
                modifier = Modifier
                    .height(20.dp),
                checked = item.model.checked,
                enabled = item.model.onChange != null,
                onCheckedChange = null,
            )
        },
        onClick = item.model.onChange?.partially1(!item.model.checked),
    )
}

@Composable
fun FilterSwitchItemCounter(
    counter: IntFieldModel,
) {
    Surface(
        modifier = Modifier
            .height(32.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row {
            val updatedOnChange = rememberUpdatedState(counter.onChange)
            val canDecrement = counter.number > counter.min
            val canIncrement = counter.number < counter.max
            FilterSwitchItemCounterButton(
                enabled = canDecrement,
                icon = Icons.Outlined.ArrowDownward,
                onChange = {
                    val newValue = counter.number
                        .minus(1)
                        .coerceIn(counter.min..counter.max)
                    val hasChanged = newValue != counter.number
                    if (hasChanged) {
                        updatedOnChange.value?.invoke(newValue)
                    }
                    hasChanged
                },
            )
            Text(
                modifier = Modifier
                    .padding(
                        horizontal = 4.dp,
                        vertical = 2.dp,
                    )
                    .widthIn(min = 18.dp)
                    .animateContentSize()
                    .align(Alignment.CenterVertically),
                text = counter.number.toString(),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            FilterSwitchItemCounterButton(
                enabled = canIncrement,
                icon = Icons.Outlined.ArrowUpward,
                onChange = {
                    val newValue = counter.number
                        .plus(1)
                        .coerceIn(counter.min..counter.max)
                    val hasChanged = newValue != counter.number
                    if (hasChanged) {
                        updatedOnChange.value?.invoke(newValue)
                    }
                    hasChanged
                },
            )
        }
    }
}

@Composable
private fun FilterSwitchItemCounterButton(
    icon: ImageVector,
    enabled: Boolean,
    onChange: (() -> Boolean)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    var lastJob by remember {
        mutableStateOf<Job?>(null)
    }

    val updatedLocalHapticFeedback by rememberUpdatedState(newValue = LocalHapticFeedback.current)
    val updatedOnChange by rememberUpdatedState(newValue = onChange)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(32.dp)
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        updatedLocalHapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)

                        lastJob?.cancel()
                        lastJob = coroutineScope.launch {
                            var iteration = 0
                            while (isActive) {
                                val shouldAbort = updatedOnChange?.invoke() == false
                                if (shouldAbort) {
                                    break
                                }

                                val delayMs = 5f / (1f + iteration)
                                delay(
                                    delayMs
                                        .times(100)
                                        .toLong(),
                                )
                                iteration += 1
                            }
                        }
                    },
                    onDragCancel = {
                        lastJob?.cancel()
                    },
                    onDragEnd = {
                        lastJob?.cancel()
                    },
                ) { _, _ ->
                    // Do nothing.
                }
            }
            .clickable(enabled = enabled) {
                updatedOnChange?.invoke()
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = enabled,
            enter = fadeIn() + scaleIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun FilterTextItem(
    item: GeneratorState.Filter.Item.Text,
) {
    FlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .padding(vertical = 2.dp),
        leading = if (item.icon != null) {
            icon<RowScope>(item.icon)
        } else {
            null
        },
        label = item.title,
        value = item.model,
        textStyle = LocalTextStyle.current.copy(
            fontFamily = monoFontFamily,
        ),
    )
}

@Composable
private fun FilterEnumItem(
    item: GeneratorState.Filter.Item.Enum,
) {
    FlatDropdown(
        leading = if (item.icon != null) {
            icon<RowScope>(item.icon)
        } else {
            null
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(item.title)
                },
                text = {
                    Text(item.model.value)
                },
            )
        },
        dropdown = item.model.dropdown,
    )
}

@Composable
private fun FilterSectionItem(
    item: GeneratorState.Filter.Item.Section,
) {
    Section(
        text = item.text,
    )
}

@Composable
private fun ColumnScope.DecoratedSliderLayout(
    length: @Composable (Modifier) -> Unit,
    slider: @Composable (Modifier) -> Unit,
    sliderLabel: @Composable (Modifier, Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPadding),
    ) {
        Text(
            modifier = Modifier
                .weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            text = stringResource(Res.string.length),
        )
        length(
            Modifier
                .alignByBaseline(),
        )
    }
    slider(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPadding),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val n = 9
        for (i in 0..n) {
            val ratio = i.toFloat() / n.toFloat()
            sliderLabel(
                Modifier,
                ratio,
            )
        }
    }
}

@Composable
fun ColumnScope.DecoratedSlider(
    filterLength: GeneratorState.Filter.Length,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val onLengthChange = rememberUpdatedState(newValue = filterLength.onChange)

    var filterLengthTemp by kotlin.run {
        val length = filterLength.value
        remember(filterLength) {
            mutableStateOf(length.toFloat())
        }
    }
    val counter = remember(filterLengthTemp.roundToInt()) {
        IntFieldModel(
            number = filterLengthTemp.roundToLong(),
            min = filterLength.min.toLong(),
            max = filterLength.max.toLong(),
            onChange = { value ->
                onLengthChange.value?.invoke(value.toInt())
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPadding),
    ) {
        Text(
            modifier = Modifier
                .weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            text = stringResource(Res.string.length),
        )
        FilterSwitchItemCounter(
            counter = counter,
        )
    }
    val sliderValueRange = filterLength.run { min.toFloat()..max.toFloat() }
    val sliderSteps = filterLength.run { max - min } - 1
    Slider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPadding),
        value = filterLengthTemp,
        valueRange = sliderValueRange,
        steps = sliderSteps,
        interactionSource = interactionSource,
        onValueChange = { length ->
            filterLengthTemp = length
        },
        onValueChangeFinished = {
            val length = filterLengthTemp.roundToInt()
            onLengthChange.value?.invoke(length)
        },
    )
}

@Composable
private fun ColumnScope.DecoratedSkeletonSlider(
    modifier: Modifier = Modifier,
) {
    DecoratedSliderLayout(
        length = { m ->
            val contentColor = LocalContentColor.current
                .combineAlpha(DisabledEmphasisAlpha)
            Box(
                modifier = m
                    .shimmer()
                    .heightIn(min = 16.dp)
                    .widthIn(min = 20.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor),
            )
        },
        slider = { m ->
            SkeletonSlider(
                modifier = m,
            )
        },
        sliderLabel = { m, _ ->
            val contentColor = LocalContentColor.current
                .combineAlpha(DisabledEmphasisAlpha)
            Box(
                modifier = m
                    .shimmer()
                    .heightIn(min = 14.dp)
                    .widthIn(min = 18.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor),
            )
        },
    )
}

@Composable
fun AutoResizeText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    maxFontSize: Float = 32f,
    minFontSize: Float = 14f,
    maxLines: Int = Int.MAX_VALUE,
) {
    val fontSize = run {
        val ratio = 1f - (text.length / 128f)
            .coerceAtMost(1f)
        minFontSize + (maxFontSize - minFontSize) * ratio
    }
    Text(
        modifier = modifier,
        text = text,
        fontFamily = monoFontFamily,
        fontSize = fontSize.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleLarge,
    )
}
