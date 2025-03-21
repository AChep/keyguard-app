package com.artemchep.keyguard.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import arrow.core.andThen
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.home.vault.component.VaultItemIcon2
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevationSemi
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

private inline val flatItemSmallCornerSizeDp get() = 4.dp

private val flatItemSmallCornerSize = CornerSize(flatItemSmallCornerSizeDp)

private val flatItemSmallShape = RoundedCornerShape(flatItemSmallCornerSizeDp)

private val defaultPaddingValues: PaddingValues = PaddingValues(
    horizontal = 8.dp,
    vertical = 2.dp,
)

private val defaultContentPadding: PaddingValues = PaddingValues(
    horizontal = 8.dp,
    vertical = 8.dp,
)

sealed interface ContextItem {
    data class Section(
        val title: String? = null,
    ) : ContextItem

    data class Custom(
        val content: @Composable () -> Unit,
    ) : ContextItem
}

data class FlatItemAction(
    val id: String? = null,
    val icon: ImageVector? = null,
    val leading: (@Composable () -> Unit)? = null,
    val trailing: (@Composable () -> Unit)? = null,
    val title: TextHolder,
    val text: TextHolder? = null,
    val type: Type? = null,
    val selected: Boolean = false,
    val onClick: (() -> Unit)? = {},
) : ContextItem {
    companion object;

    enum class Type {
        COPY,
        DOWNLOAD,
        VIEW,
    }
}

inline fun buildContextItems(
    vararg items: List<ContextItem>,
    block: ContextItemBuilder.() -> Unit,
) = ContextItemBuilder(items = items).apply(block).build()

class ContextItemBuilder(
    vararg items: List<ContextItem>,
) {
    private val items = kotlin.run {
        val out = mutableListOf<ContextItem>()
        items.forEach { list ->
            if (list.isEmpty()) {
                return@forEach
            }

            val header = list.first()
            if (header !is ContextItem.Section) {
                out += ContextItem.Section()
            }

            out += list
        }
        out
    }

    inline fun section(
        title: String? = null,
        block: ContextItemBuilder.() -> Unit,
    ) {
        val sectionItems = buildContextItems(block = block)
        if (sectionItems.isEmpty()) {
            return
        }

        this += ContextItem.Section(title = title)
        this += sectionItems
    }

    operator fun plusAssign(item: ContextItem?) {
        items += item
            ?: return
    }

    operator fun plusAssign(item: Collection<ContextItem>) {
        items += item
    }

    fun build(): PersistentList<ContextItem> = sequence<ContextItem> {
        items.forEachIndexed { index, item ->
            // Skip a first item if it has no title.
            if (
                index == 0 &&
                item is ContextItem.Section &&
                item.title == null
            ) {
                return@forEachIndexed
            }

            yield(item)
        }
    }.toPersistentList()
}


@JvmName("FlatItemActionNullable")
fun CopyText.FlatItemAction(
    title: TextHolder,
    value: String?,
    hidden: Boolean = false,
    type: CopyText.Type = CopyText.Type.VALUE,
) = value?.let {
    FlatItemAction(
        title = title,
        value = it,
        hidden = hidden,
        type = type,
    )
}

fun CopyText.FlatItemAction(
    leading: (@Composable () -> Unit)? = null,
    title: TextHolder,
    value: String,
    hidden: Boolean = false,
    type: CopyText.Type = CopyText.Type.VALUE,
) = FlatItemAction(
    leading = leading,
    icon = Icons.Outlined.ContentCopy,
    title = title,
    text = value.takeUnless { hidden }
        ?.let(TextHolder::Value),
    type = FlatItemAction.Type.COPY,
    onClick = {
        copy(
            text = value,
            hidden = hidden,
            type = type,
        )
    },
)

@Composable
fun FlatDropdown(
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    backgroundColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
    dropdown: List<ContextItem> = emptyList(),
    actions: List<FlatItemAction> = emptyList(),
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    paddingValues: PaddingValues = defaultPaddingValues,
    contentPadding: PaddingValues = defaultContentPadding,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = dropdown.isNotEmpty() || onClick != null || onLongClick != null,
) {
    FlatDropdownLayout(
        modifier = modifier,
        elevation = elevation,
        backgroundColor = backgroundColor,
        content = content,
        dropdown = if (dropdown.isNotEmpty()) {
            // composable
            {
                dropdown.forEach { action ->
                    DropdownMenuItemFlat(
                        action = action,
                    )
                }
            }
        } else {
            null
        },
        actions = actions,
        leading = leading,
        trailing = trailing,
        paddingValues = paddingValues,
        contentPadding = contentPadding,
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
    )
}

@Composable
fun FlatDropdownLayout(
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    backgroundColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
    dropdown: (@Composable DropdownScope.() -> Unit)? = null,
    actions: List<FlatItemAction> = emptyList(),
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    paddingValues: PaddingValues = defaultPaddingValues,
    contentPadding: PaddingValues = defaultContentPadding,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = dropdown != null || onClick != null || onLongClick != null,
) {
    var isContentDropdownExpanded by remember { mutableStateOf(false) }
    FlatItemLayout(
        modifier = modifier,
        elevation = elevation,
        backgroundColor = backgroundColor,
        content = {
            content()

            // Inject the dropdown popup to the bottom of the
            // content.
            val onDismissRequest = {
                isContentDropdownExpanded = false
            }
            DropdownMenu(
                expanded = isContentDropdownExpanded,
                onDismissRequest = onDismissRequest,
                modifier = Modifier
                    .widthIn(min = DropdownMinWidth),
            ) {
                val scope = DropdownScopeImpl(this, onDismissRequest = onDismissRequest)
                dropdown?.invoke(scope)
            }
        },
        actions = actions,
        leading = leading,
        trailing = trailing,
        paddingValues = paddingValues,
        contentPadding = contentPadding,
        onClick = when {
            onClick != null -> onClick
            dropdown != null -> {
                // lambda
                {
                    isContentDropdownExpanded = !isContentDropdownExpanded
                }
            }

            else -> null
        },
        onLongClick = onLongClick,
        enabled = enabled,
    )
}

interface DropdownScope : ColumnScope {
    val onDismissRequest: () -> Unit
}

fun DropdownScope.dismissOnClick(block: () -> Unit) = block.andThen { onDismissRequest() }

class DropdownScopeImpl(
    parent: ColumnScope,
    override val onDismissRequest: () -> Unit,
) : DropdownScope, ColumnScope by parent

@Composable
fun <T> ColumnScope.DropdownMenuExpandableContainer(
    dropdownScope: DropdownScope,
    list: List<T>,
    maxItems: Int = 4,
    render: @Composable (T) -> Unit,
) {
    var maximized by remember {
        mutableStateOf(false)
    }
    list.forEachIndexed { i, el ->
        if (!maximized) {
            if (i == maxItems) {
                dropdownScope.DropdownMenuItemFlatLayout(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            maximized = true
                        },
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        modifier = Modifier
                            .padding(1.dp)
                            .size(18.dp),
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                    )
                }
            }
            if (i >= maxItems) {
                return@forEachIndexed
            }
        }
        render(el)
    }
}

@Composable
fun FlatItem(
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    backgroundColor: Color = Color.Unspecified,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null,
    actions: List<FlatItemAction> = emptyList(),
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    paddingValues: PaddingValues = defaultPaddingValues,
    contentPadding: PaddingValues = defaultContentPadding,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = onClick != null || onLongClick != null,
) {
    FlatItemLayout(
        modifier = modifier,
        elevation = elevation,
        backgroundColor = backgroundColor,
        content = {
            FlatItemTextContent(
                title = title,
                text = text,
            )
        },
        actions = actions,
        leading = leading,
        trailing = trailing,
        paddingValues = paddingValues,
        contentPadding = contentPadding,
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
    )
}

@Composable
fun ColumnScope.FlatItemTextContent(
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    tint: Color = LocalContentColor.current,
    compact: Boolean = false,
) {
    val titleStyle = MaterialTheme.typography.bodyLarge
    val textStyle =
        if (!compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall
    if (title != null) {
        val contentColor = tint
            .copy(alpha = 1f)
            .combineAlpha(LocalContentColor.current.alpha)
        CompositionLocalProvider(
            LocalTextStyle provides titleStyle
                .copy(color = contentColor),
        ) {
            title()
        }
    }
    if (text != null) {
        val contentColor = tint
            .copy(alpha = 1f)
            .combineAlpha(LocalContentColor.current.alpha)
            .combineAlpha(MediumEmphasisAlpha)
        CompositionLocalProvider(
            LocalTextStyle provides textStyle
                .copy(color = contentColor),
        ) {
            text()
        }
    }
}

val DefaultEmphasisAlpha = 1f
val HighEmphasisAlpha = 0.87f
val MediumEmphasisAlpha = 0.6f
val DisabledEmphasisAlpha = 0.38f

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun FlatItemLayout(
    modifier: Modifier = Modifier,
    paddingValues: PaddingValues = defaultPaddingValues,
    contentPadding: PaddingValues = defaultContentPadding,
    elevation: Dp = 0.dp,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = backgroundColor
        .takeIf { it.isSpecified }
        ?.let { contentColorFor(it) }
        ?: LocalContentColor.current,
    content: @Composable ColumnScope.() -> Unit,
    actions: List<FlatItemAction> = emptyList(),
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = onClick != null,
) {
    ContentColorColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(paddingValues),
        color = contentColor,
    ) {
        val backgroundModifier = kotlin.run {
            // Check if there's actually a background color
            // to render.
            if (
                backgroundColor.isUnspecified &&
                elevation == 0.dp
            ) {
                return@run Modifier
            }

            val bg = backgroundColor.takeIf { it.isSpecified }
                ?: Color.Transparent
            val fg = MaterialTheme.colorScheme.surfaceColorAtElevationSemi(elevation)
            Modifier
                .background(fg.compositeOver(bg))
        }

        val shape = MaterialTheme.shapes.medium
        val shapeBottomCornerDp by kotlin.run {
            val target = if (actions.isEmpty()) {
                16.dp
            } else {
                flatItemSmallCornerSizeDp
            }
            animateDpAsState(targetValue = target)
        }

        val haptic by rememberUpdatedState(LocalHapticFeedback.current)
        val updatedOnClick by rememberUpdatedState(onClick)
        val updatedOnLongClick by rememberUpdatedState(onLongClick)

        Row(
            modifier = Modifier
                .clip(
                    shape.copy(
                        bottomStart = CornerSize(shapeBottomCornerDp),
                        bottomEnd = CornerSize(shapeBottomCornerDp),
                    ),
                )
                .then(backgroundModifier)
                .then(
                    if ((onClick != null || onLongClick != null) && enabled) {
                        Modifier
                            .combinedClickable(
                                onLongClick = if (onLongClick != null) {
                                    // lambda
                                    {
                                        updatedOnLongClick?.invoke()
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                } else {
                                    null
                                },
                            ) {
                                updatedOnClick?.invoke()
                            }
                            .rightClickable {
                                updatedOnLongClick?.invoke()
                            }
                    } else {
                        Modifier
                    },
                )
                .minimumInteractiveComponentSize()
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides LocalContentColor.current
                    .let { color ->
                        if (enabled) {
                            color
                        } else {
                            color.combineAlpha(alpha = DisabledEmphasisAlpha)
                        }
                    },
            ) {
                if (leading != null) {
                    CompositionLocalProvider(
                        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
                    ) {
                        leading()
                    }
                    Spacer(Modifier.width(16.dp))
                }
                Column(
                    modifier = Modifier
                        .weight(1f),
                ) {
                    content()
                }
                if (trailing != null) {
                    Spacer(Modifier.width(16.dp))
                    trailing()
                }
            }
        }
        actions.forEachIndexed { actionIndex, action ->
            val actionShape = if (actions.size - 1 == actionIndex) {
                shape.copy(
                    topStart = flatItemSmallCornerSize,
                    topEnd = flatItemSmallCornerSize,
                )
            } else {
                flatItemSmallShape
            }

            Spacer(modifier = Modifier.height(2.dp))
            val updatedOnClick by rememberUpdatedState(action.onClick)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(actionShape)
                    .then(backgroundModifier)
                    .then(
                        if (action.onClick != null) {
                            Modifier
                                .clickable {
                                    updatedOnClick?.invoke()
                                }
                        } else {
                            Modifier
                        },
                    )
                    .minimumInteractiveComponentSize()
                    .padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlatItemActionContent(
                    action = action,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun ContentColorColumn(
    modifier: Modifier = Modifier,
    color: Color,
    content: @Composable ColumnScope.() -> Unit,
) = Column(modifier = modifier) {
    CompositionLocalProvider(
        LocalContentColor provides color,
    ) {
        content()
    }
}

@Composable
fun RowScope.FlatItemActionContent(
    action: FlatItemAction,
    compact: Boolean = false,
) {
    CompositionLocalProvider(
        LocalContentColor provides LocalContentColor.current
            .let { color ->
                if (action.onClick != null) {
                    color
                } else {
                    color.combineAlpha(DisabledEmphasisAlpha)
                }
            },
    ) {
        if (action.icon != null || action.leading != null) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(16.dp),
            ) {
                if (action.icon != null) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                    )
                }
                action.leading?.invoke()
            }
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            FlatItemTextContent(
                title = {
                    Text(
                        text = textResource(action.title),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                text = if (action.text != null) {
                    // composable
                    {
                        Text(
                            text = textResource(action.text),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    null
                },
                compact = compact,
            )
        }
        if (action.trailing != null) {
            Spacer(Modifier.width(8.dp))
            action.trailing.invoke()
        }
    }
}

@Composable
fun Avatar(
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current
        .let {
            it.copy(alpha = 0.05f * LocalContentColor.current.alpha)
        },
    content: @Composable BoxScope.() -> Unit,
) {
    val contentColor = LocalContentColor.current.copy(alpha = DisabledEmphasisAlpha)
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(color),
    ) {
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarBuilder(
    modifier: Modifier = Modifier,
    icon: VaultItemIcon,
    accent: Color,
    active: Boolean,
    badge: @Composable () -> Unit,
) = Box(
    modifier = modifier,
) {
    val avatarColor = accent
        .takeIf {
            val fits = icon is VaultItemIcon.VectorIcon ||
                    icon is VaultItemIcon.TextIcon
            fits && active
        }
        ?: MaterialTheme.colorScheme.surfaceColorAtElevationSemi(
            LocalAbsoluteTonalElevation.current + 8.dp,
        )
    Avatar(
        color = avatarColor,
    ) {
        VaultItemIcon2(
            icon = icon,
        )
    }

    val backgroundColor = if (!active) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    AvatarBadgeSurface(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .widthIn(max = 36.dp)
            .wrapContentWidth(
                align = Alignment.Start,
                unbounded = true,
            ),
        backgroundColor = backgroundColor,
        content = badge,
    )
}

@Composable
fun AvatarBadgeSurface(
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    content: @Composable () -> Unit,
) {
    val contentColor = contentColorFor(backgroundColor)
    FlowRow(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(backgroundColor),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
        ) {
            content()
        }
    }
}

@Composable
fun AvatarBadgeIcon(
    modifier: Modifier = Modifier,
    imageVector: ImageVector,
) {
    Icon(
        modifier = modifier
            .size(16.dp)
            .padding(1.dp),
        imageVector = imageVector,
        contentDescription = null,
    )
}
