package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import arrow.core.partially1
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.fileName
import com.artemchep.keyguard.common.model.fileSize
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.favicon.FaviconImage
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.AvatarBuilder
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.DropdownScopeImpl
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.IconSmallBox
import com.artemchep.keyguard.ui.icons.KeyguardAttachment
import com.artemchep.keyguard.ui.icons.KeyguardFavourite
import com.artemchep.keyguard.ui.rightClickable
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.isDark
import com.artemchep.keyguard.ui.theme.selectedContainer
import com.artemchep.keyguard.ui.util.HorizontalDivider
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.collections.immutable.ImmutableList
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
            icon = {
                Icon(Icons.Outlined.SearchOff, null)
            },
            text = {
                Text(
                    text = stringResource(Res.strings.vault_main_no_suggested_items),
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
                    text = stringResource(Res.strings.items_empty_label),
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
    )
}

@Composable
fun VaultListItemButton(
    modifier: Modifier = Modifier,
    item: VaultItem2.Button,
) {
    val contentColor = MaterialTheme.colorScheme.primary
    FlatItem(
        modifier = modifier,
        leading = {
            CompositionLocalProvider(
                LocalContentColor provides contentColor,
            ) {
                val leading = item.leading
                if (leading != null) {
                    Row(
                        modifier = Modifier
                            .widthIn(min = 36.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        leading.invoke()
                    }
                }
            }
        },
        title = {
            Text(
                text = item.title,
                color = contentColor,
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
) {
    if (text != null) {
        Text(
            modifier = modifier
                .padding(
                    vertical = 16.dp,
                    horizontal = Dimens.horizontalPadding,
                ),
            text = if (caps) text.uppercase() else text,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    } else {
        HorizontalDivider(
            modifier = modifier
                .padding(
                    vertical = 4.dp,
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
                horizontal = Dimens.horizontalPadding,
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
    FlatItemLayout2(
        modifier = modifier,
        backgroundColor = backgroundColor,
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = item.title,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
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
            DropdownMenu(
                modifier = Modifier
                    .widthIn(min = DropdownMinWidth),
                expanded = dropdownExpandedState.value,
                onDismissRequest = onDismissRequest,
            ) {
                val scope = DropdownScopeImpl(this, onDismissRequest = onDismissRequest)
                with(scope) {
                    dropdownActions.forEach { action ->
                        DropdownMenuItemFlat(
                            action = action,
                        )
                    }
                }
            }
        },
        leading = {
            AccountListItemTextIcon(
                modifier = Modifier,
                item = item,
            )
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
                ?: stringResource(Res.strings.empty_value),
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

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun FlatItemLayout2(
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    content: @Composable ColumnScope.() -> Unit,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = onClick != null,
) {
    val haptic by rememberUpdatedState(LocalHapticFeedback.current)
    val background = run {
        Modifier
            .drawBehind {
                drawRect(backgroundColor)
            }
    }
    val clickable = run {
        val onClickState = rememberUpdatedState(onClick)
        val onLongClickState = rememberUpdatedState(onLongClick)
        Modifier
            .combinedClickable(
                enabled = onClick != null,
                onLongClick = {
                    val lambda = onLongClickState.value
                    if (lambda != null) {
                        lambda.invoke()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
            ) {
                onClickState.value?.invoke()
            }
            .rightClickable(onLongClick)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 8.dp,
                vertical = 2.dp,
            )
            .clip(MaterialTheme.shapes.medium)
            .then(background)
            .then(clickable)
            .minimumInteractiveComponentSize()
            .padding(
                horizontal = 8.dp,
                vertical = 8.dp,
            ),
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
                    LocalMinimumInteractiveComponentEnforcement provides false,
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
    return surfaceTint.copy(alpha = alpha)
}

@Composable
fun BoxScope.VaultItemIcon2(
    icon: VaultItemIcon,
    modifier: Modifier = Modifier,
) {
    when (icon) {
        is VaultItemIcon.VectorIcon -> {
            Icon(
                modifier = modifier
                    .align(Alignment.Center),
                imageVector = icon.imageVector,
                contentDescription = null,
                tint = Color.Black,
            )
        }

        is VaultItemIcon.TextIcon -> {
            Text(
                modifier = modifier
                    .align(Alignment.Center),
                text = icon.text,
                color = Color.Black
                    .combineAlpha(MediumEmphasisAlpha),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
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
            FaviconImage(
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
                Icon(
                    modifier = Modifier
                        .size(16.dp)
                        .padding(1.dp),
                    imageVector = Icons.Outlined.KeyguardFavourite,
                    contentDescription = null,
                )
            }
            if (item.source.reprompt) {
                Icon(
                    modifier = Modifier
                        .size(16.dp)
                        .padding(1.dp),
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                )
            }
            if (item.attachments) {
                Icon(
                    modifier = Modifier
                        .size(16.dp)
                        .padding(1.dp),
                    imageVector = Icons.Outlined.KeyguardAttachment,
                    contentDescription = null,
                )
            }
        },
    )
}
