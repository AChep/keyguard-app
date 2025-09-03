package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import arrow.core.partially1
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.fileName
import com.artemchep.keyguard.common.model.fileSize
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.ui.icons.FaviconIcon
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.AvatarBadgeIcon
import com.artemchep.keyguard.ui.AvatarBuilder
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.DropdownScope
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.KeyguardDropdownMenu
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.IconSmallBox
import com.artemchep.keyguard.ui.icons.KeyguardAttachment
import com.artemchep.keyguard.ui.icons.KeyguardFavourite
import com.artemchep.keyguard.ui.rightClickable
import com.artemchep.keyguard.ui.surface.LocalSurfaceElevation
import com.artemchep.keyguard.ui.surface.surfaceNextGroupColorToElevationColor
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.LocalExpressive
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.isDark
import com.artemchep.keyguard.ui.theme.selectedContainer
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.painterResource
import kotlin.math.ln

@Composable
fun VaultListItem(
    modifier: Modifier = Modifier,
    item: VaultItem2,
) = when (item) {
    is VaultItem2.QuickFilters -> {
    }

    is VaultItem2.NoSuggestions -> {
        EmptyView(
            largeArtwork = false,
            icon = {
                Icon(Icons.Outlined.SearchOff, null)
            },
            text = {
                Text(
                    text = stringResource(Res.string.vault_main_no_suggested_items),
                )
            },
        )
    }

    is VaultItem2.NoItems -> {
        EmptyView(
            icon = {
                Icon(Icons.Outlined.SearchOff, null)
            },
            text = {
                Text(
                    text = stringResource(Res.string.items_empty_label),
                )
            },
        )
    }

    is VaultItem2.Section -> VaultListItemSection(modifier, item)
    is VaultItem2.Button -> VaultListItemButton(modifier, item)
    is VaultItem2.Item -> VaultListItemText(modifier, item)
}

@Composable
fun VaultListItemSection(
    modifier: Modifier = Modifier,
    item: VaultItem2.Section,
) {
    val text = item.text?.let { textResource(it) }
    Section(
        modifier = modifier,
        text = text,
        caps = item.caps,
        expressive = true,
    )
}

@Composable
fun VaultListItemButton(
    modifier: Modifier = Modifier,
    item: VaultItem2.Button,
) {
    FlatItemSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        leading = {
            val leading = item.leading
            if (leading != null) {
                Avatar {
                    leading.invoke()
                }
            }
        },
        title = {
            Text(
                text = item.title,
            )
        },
        onClick = item.onClick,
    )
}

@Composable
fun Section(
    modifier: Modifier = Modifier,
    text: String? = null,
    caps: Boolean = true,
    expressive: Boolean = false,
) {
    if (text != null) {
        Text(
            modifier = modifier
                .padding(
                    vertical = Dimens.contentPadding
                        .coerceAtLeast(16.dp),
                    horizontal = Dimens.textHorizontalPadding,
                ),
            text = if (caps) text.uppercase() else text,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    } else if (expressive) {
        Spacer(
            modifier = Modifier
                .height(24.dp),
        )
    } else {
        HorizontalDivider(
            modifier = modifier
                .padding(
                    vertical = 8.dp,
                ),
        )
    }
}

@Composable
fun LargeSection(
    modifier: Modifier = Modifier,
    text: String,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .padding(
                vertical = 16.dp,
                horizontal = Dimens.textHorizontalPadding,
            ),
    ) {
        val textStyle = MaterialTheme.typography.labelLarge
            .copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
        ProvideTextStyle(textStyle) {
            Text(
                modifier = Modifier
                    .weight(1f),
                text = text,
            )
            if (trailing != null) {
                Spacer(
                    modifier = Modifier
                        .width(8.dp),
                )
                trailing()
            }
        }
    }
}

@Composable
fun VaultListItemText(
    modifier: Modifier = Modifier,
    item: VaultItem2.Item,
    leading: (@Composable RowScope.(@Composable () -> Unit) -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val dropdownExpandedState = remember { mutableStateOf(false) }
    val dropdownExpand = remember {
        // lambda
        dropdownExpandedState::value::set
            .partially1(true)
    }
    val dropdownActions = item.action
        .let { it as? VaultItem2.Item.Action.Dropdown }
        .let { it?.actions.orEmpty() }
    // If we suddenly switch the mode of the items or items disappear,
    // then collapse the existing popup window.
    if (dropdownActions.isEmpty()) {
        dropdownExpandedState.value = false
    }

    val localState by item.localStateFlow.collectAsState()

    val onClick = localState.selectableItemState.onClick
    // fallback to default
        ?: when (item.action) {
            is VaultItem2.Item.Action.Dropdown -> dropdownExpand
            is VaultItem2.Item.Action.Go -> item.action.onClick
        }.takeIf { localState.selectableItemState.can }
    val onLongClick = localState.selectableItemState.onLongClick

    val backgroundColor = when {
        localState.selectableItemState.selected -> MaterialTheme.colorScheme.primaryContainer
        localState.openedState.isOpened ->
            MaterialTheme.colorScheme.selectedContainer
                .takeIf { LocalHasDetailPane.current }
                ?: Color.Unspecified

        else -> Color.Unspecified
    }
    val badgeColor = if (backgroundColor.isSpecified) {
        LocalContentColor.current
            .combineAlpha(0.03f)
            .compositeOver(backgroundColor)
    } else {
        backgroundColor
    }
    FlatItemLayoutExpressive(
        modifier = modifier,
        backgroundColor = backgroundColor,
        shapeState = item.shapeState,
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

            content?.invoke(this)

            if (item.token != null) {
                VaultViewTotpBadge2(
                    modifier = Modifier
                        .padding(top = 8.dp),
                    copyText = item.copyText,
                    totpToken = item.token,
                )
            }

            SmartBadgeList(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                items = item.passkeys,
                key = { it.source.credentialId },
            ) {
                SmartBadge(
                    modifier = Modifier,
                    icon = {
                        IconSmallBox(
                            main = Icons.Outlined.Key,
                        )
                    },
                    title = it.source.userDisplayName,
                    text = it.source.rpId,
                    onClick = it.onClick,
                )
            }

            SmartBadgeList(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                items = item.attachments2,
                key = { it.source.id },
            ) {
                SmartBadge(
                    modifier = Modifier,
                    icon = {
                        IconSmallBox(
                            main = Icons.Outlined.KeyguardAttachment,
                        )
                    },
                    title = it.source.fileName(),
                    text = it.source.fileSize()
                        ?.let(::humanReadableByteCountSI).orEmpty(),
                    // TODO: I'm not sure what we can do by clicking
                    //  on the attachment. Would be nice to support the
                    //  whole feature set of the attachment item at some
                    //  point, although that would be pretty complicated.
                    onClick = null,
                )
            }

            // Inject the dropdown popup to the bottom of the
            // content.
            val onDismissRequest = {
                dropdownExpandedState.value = false
            }
            KeyguardDropdownMenu(
                expanded = dropdownExpandedState.value,
                onDismissRequest = onDismissRequest,
            ) {
                dropdownActions.forEach { action ->
                    DropdownMenuItemFlat(
                        action = action,
                    )
                }
            }
        },
        leading = {
            if (leading != null) {
                leading {
                    AccountListItemTextIcon(
                        modifier = Modifier,
                        item = item,
                    )
                }
            } else {
                AccountListItemTextIcon(
                    modifier = Modifier,
                    item = item,
                )
            }
        },
        trailing = {
            when (item.feature) {
                is VaultItem2.Item.Feature.Organization -> {
                    val backgroundColor = if (MaterialTheme.colorScheme.isDark) {
                        item.feature.accentColors.dark
                    } else {
                        item.feature.accentColors.light
                    }
                    val contentColor = if (backgroundColor.luminance() > 0.5f) {
                        Color.Black
                    } else {
                        Color.White
                    }.combineAlpha(0.8f).compositeOver(backgroundColor)
                    Text(
                        modifier = Modifier
                            .widthIn(max = 80.dp)
                            .background(
                                backgroundColor,
                                MaterialTheme.shapes.small,
                            )
                            .padding(
                                horizontal = 6.dp,
                                vertical = 2.dp,
                            ),
                        text = item.feature.name,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                is VaultItem2.Item.Feature.Totp,
                is VaultItem2.Item.Feature.None,
                    -> {
                }
            }

            if (item.source.hasError) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    contentDescription = null,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            val tqry = when {
                localState.selectableItemState.selecting -> Try.CHECKBOX
                item.action is VaultItem2.Item.Action.Go -> Try.CHEVRON
                else -> Try.NONE
            }
            Crossfade(
                modifier = Modifier
                    .size(
                        width = 36.dp,
                        height = 36.dp,
                    ),
                targetState = tqry,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (it) {
                        Try.CHECKBOX -> {
                            Checkbox(
                                checked = localState.selectableItemState.selected,
                                onCheckedChange = null,
                            )
                        }

                        Try.CHEVRON -> {
                            ChevronIcon()
                        }

                        Try.NONE -> {}
                    }
                }
            }
        },
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
private inline fun <T : Any> SmartBadgeList(
    modifier: Modifier = Modifier,
    items: ImmutableList<T>,
    crossinline key: (T) -> Any,
    crossinline item: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) {
        return
    }

    SmartBadgeListContainer(
        modifier = modifier,
    ) {
        items.forEach {
            key(key(it)) {
                item(it)
            }
        }
    }
}

@Composable
private fun SmartBadgeListContainer(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun SmartBadge(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    title: String?,
    text: String?,
    onClick: (() -> Unit)? = null,
) {
    val updatedOnClick by rememberUpdatedState(onClick)

    val backgroundModifier = if (updatedOnClick != null) {
        val tintColor = MaterialTheme.colorScheme
            .surfaceColorAtElevationSemi(1.dp)
        Modifier
            .background(tintColor)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .then(backgroundModifier)
            .clickable(enabled = updatedOnClick != null) {
                updatedOnClick?.invoke()
            }
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp),
        ) {
            icon()
        }
        Spacer(
            modifier = Modifier
                .width(8.dp),
        )
        Text(
            modifier = Modifier
                .widthIn(max = 128.dp)
                .alignByBaseline(),
            text = title
                ?: stringResource(Res.string.empty_value),
            color = if (title != null) {
                LocalContentColor.current
            } else {
                LocalContentColor.current
                    .combineAlpha(DisabledEmphasisAlpha)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (text != null) {
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
            Text(
                modifier = Modifier
                    .widthIn(max = 128.dp)
                    .alignByBaseline(),
                text = text,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private enum class Try {
    CHECKBOX,
    CHEVRON,
    NONE,
}

val expressiveInnerCornerSize = CornerSize(4.dp)

@Composable
fun FlatDropdownSimpleExpressive(
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = backgroundColor
        .takeIf { it.isSpecified }
        ?.let { contentColorFor(it) }
        ?: LocalContentColor.current,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    content: @Composable ColumnScope.() -> Unit,
    dropdown: List<ContextItem> = emptyList(),
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = dropdown.isNotEmpty() || onClick != null || onLongClick != null,
) {
    FlatDropdownLayoutExpressive(
        modifier = modifier,
        elevation = elevation,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        shapeState = shapeState,
        expressive = expressive,
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
        footer = footer,
        leading = leading,
        trailing = trailing,
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
    )
}

@Composable
fun FlatDropdownLayoutExpressive(
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = backgroundColor
        .takeIf { it.isSpecified }
        ?.let { contentColorFor(it) }
        ?: LocalContentColor.current,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    content: @Composable ColumnScope.() -> Unit,
    dropdown: (@Composable DropdownScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = dropdown != null || onClick != null || onLongClick != null,
) {
    var isContentDropdownExpanded by remember { mutableStateOf(false) }
    FlatItemLayoutExpressive(
        modifier = modifier,
        elevation = elevation,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        shapeState = shapeState,
        expressive = expressive,
        content = {
            content()

            // Inject the dropdown popup to the bottom of the
            // content.
            val onDismissRequest = {
                isContentDropdownExpanded = false
            }
            KeyguardDropdownMenu(
                expanded = isContentDropdownExpanded,
                onDismissRequest = onDismissRequest,
            ) {
                dropdown?.invoke(this)
            }
        },
        footer = footer,
        leading = leading,
        trailing = trailing,
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

@Composable
fun FlatItemSimpleExpressive(
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = backgroundColor
        .takeIf { it.isSpecified }
        ?.let { contentColorFor(it) }
        ?: LocalContentColor.current,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = onClick != null,
) = FlatItemLayoutExpressive(
    modifier = modifier,
    elevation = elevation,
    backgroundColor = backgroundColor,
    contentColor = contentColor,
    shapeState = shapeState,
    expressive = expressive,
    content = {
        FlatItemTextContent(
            title = title,
            text = text,
        )
    },
    footer = footer,
    leading = leading,
    trailing = trailing,
    onClick = onClick,
    onLongClick = onLongClick,
    enabled = enabled,
)

@Composable
fun FlatItemLayoutExpressive(
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = backgroundColor
        .takeIf { it.isSpecified }
        ?.let { contentColorFor(it) }
        ?: LocalContentColor.current,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    padding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = onClick != null,
) {
    val haptic by rememberUpdatedState(LocalHapticFeedback.current)
    val background = run {
        val color = if (backgroundColor.isSpecified || elevation.value > 0f || !expressive) {
            val bg = backgroundColor.takeIf { it.isSpecified }
                ?: Color.Transparent
            val fg = MaterialTheme.colorScheme.surfaceColorAtElevationSemi(elevation)
            fg.compositeOver(bg)
        } else {
            val surfaceElevation = LocalSurfaceElevation.current
            surfaceNextGroupColorToElevationColor(surfaceElevation.to)
        }
        Modifier
            .drawBehind {
                drawRect(color)
            }
    }
    val clickable = run {
        val onClickState = rememberUpdatedState(onClick)
        Modifier
            .combinedClickable(
                enabled = onClick != null,
                onLongClick = onLongClick,
            ) {
                onClickState.value?.invoke()
            }
            .rightClickable(onLongClick)
    }

    val shape: Shape = surfaceShape(
        shapeState = shapeState,
        expressive = expressive,
    )
    val dimens = Dimens
    val outerHorizontalPadding: Dp = dimens.contentPadding
    val innerHorizontalPadding: Dp = dimens.contentPadding
    val innerVerticalPadding: Dp
    if (expressive) {
        innerVerticalPadding = 10.dp
    } else {
        innerVerticalPadding = 8.dp
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (padding != null) {
                    Modifier
                        .padding(padding)
                } else Modifier
                    .padding(
                        start = outerHorizontalPadding,
                        end = outerHorizontalPadding,
                        top = 1.dp,
                        bottom = 2.dp, // in Android notifications the margin is 3 dp
                    ),
            )
            .clip(shape)
            .then(background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(clickable)
                .minimumInteractiveComponentSize()
                .padding(
                    horizontal = innerHorizontalPadding,
                    vertical = innerVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides contentColor
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
        if (footer != null) {
            footer()
        }
    }
}

@Composable
fun rememberSecretAccentColor(
    accentLight: Color,
    accentDark: Color,
): Color {
    val color = LocalContentColor.current
    val accent = if (color.luminance() > 0.5f) accentDark else accentLight
    return accent.combineAlpha(alpha = color.alpha)
}

@Composable
fun surfaceShape(shapeState: Int, expressive: Boolean): Shape {
    val shape: Shape
    if (expressive) {
        val shapeSrc = MaterialTheme.shapes.large
        shape = when (shapeState) {
            ShapeState.START -> shapeSrc
                .copy(
                    bottomStart = expressiveInnerCornerSize,
                    bottomEnd = expressiveInnerCornerSize,
                )

            ShapeState.CENTER -> shapeSrc
                .copy(
                    topStart = expressiveInnerCornerSize,
                    topEnd = expressiveInnerCornerSize,
                    bottomStart = expressiveInnerCornerSize,
                    bottomEnd = expressiveInnerCornerSize,
                )

            ShapeState.END -> shapeSrc
                .copy(
                    topStart = expressiveInnerCornerSize,
                    topEnd = expressiveInnerCornerSize,
                )

            ShapeState.ALL -> shapeSrc
            else -> shapeSrc
        }
    } else {
        shape = MaterialTheme.shapes.large
    }
    return shape
}

@Composable
fun surfaceColorAtElevation(color: Color, elevation: Dp): Color {
    return if (color == MaterialTheme.colorScheme.surface) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(elevation)
    } else {
        color
    }
}

@Composable
fun ColorScheme.localSurfaceColorAtElevation(
    surface: Color,
    elevation: Dp,
): Color {
    val tint = surfaceColorAtElevationSemi(elevation = elevation)
    return if (tint.isSpecified) {
        tint.compositeOver(surface)
    } else {
        surface
    }
}

/**
 * Returns the [ColorScheme.surface] color with an alpha of the [ColorScheme.surfaceTint] color
 * overlaid on top of it.
 * Computes the surface tonal color at different elevation levels e.g. surface1 through surface5.
 *
 * @param elevation Elevation value used to compute alpha of the color overlay layer.
 */
fun ColorScheme.surfaceColorAtElevation(
    elevation: Dp,
): Color {
    val tint = surfaceColorAtElevationSemi(elevation = elevation)
    return if (tint.isSpecified) {
        tint.compositeOver(surface)
    } else {
        surface
    }
}

/**
 * Returns the [ColorScheme.surface] color with an alpha of the [ColorScheme.surfaceTint] color
 * overlaid on top of it.
 * Computes the surface tonal color at different elevation levels e.g. surface1 through surface5.
 *
 * @param elevation Elevation value used to compute alpha of the color overlay layer.
 */
fun ColorScheme.surfaceColorAtElevationSemi(
    elevation: Dp,
): Color {
    if (elevation == 0.dp) return Color.Unspecified
    val alpha = ((4.5f * ln(elevation.value + 1)) + 2f) / 100f
    return surfaceTint.combineAlpha(alpha = alpha)
}

@Composable
fun BoxScope.VaultItemIcon2(
    icon: VaultItemIcon,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    when (icon) {
        is VaultItemIcon.VectorIcon -> {
            Icon(
                modifier = modifier
                    .align(Alignment.Center),
                imageVector = icon.imageVector,
                contentDescription = null,
                tint = color,
            )
        }

        is VaultItemIcon.TextIcon -> {
            val circleColor = Color.White
                .combineAlpha(0.1f)
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(circleColor),
            ) {
                Text(
                    modifier = modifier
                        .align(Alignment.Center),
                    text = icon.text,
                    textAlign = TextAlign.Center,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }

        is VaultItemIcon.ImageIcon -> {
            Image(
                modifier = modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(CircleShape),
                painter = painterResource(icon.imageRes),
                contentDescription = null,
            )
        }

        is VaultItemIcon.WebsiteIcon,
        is VaultItemIcon.AppIcon,
            -> {
            FaviconIcon(
                modifier = modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(CircleShape),
                imageModel = {
                    when (icon) {
                        is VaultItemIcon.WebsiteIcon -> icon.data
                        is VaultItemIcon.AppIcon -> icon.data
                        else -> error("Unreachable statement")
                    }
                },
            )
        }
    }
}

@Composable
fun AccountListItemTextIcon(
    modifier: Modifier = Modifier,
    item: VaultItem2.Item,
) {
    val accent = rememberSecretAccentColor(
        accentLight = item.accentLight,
        accentDark = item.accentDark,
    )
    AvatarBuilder(
        modifier = modifier,
        icon = item.icon,
        accent = accent,
        active = item.source.service.remote != null,
        badge = {
            if (item.favourite) {
                AvatarBadgeIcon(
                    imageVector = Icons.Outlined.KeyguardFavourite,
                )
            }
            if (item.source.reprompt) {
                AvatarBadgeIcon(
                    imageVector = Icons.Outlined.Lock,
                )
            }
            if (item.attachments) {
                AvatarBadgeIcon(
                    imageVector = Icons.Outlined.KeyguardAttachment,
                )
            }
        },
    )
}
