package com.artemchep.keyguard.wear.feature.vault

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.AccountListItemTextIcon
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.empty_value
import com.artemchep.keyguard.res.items_empty_label
import com.artemchep.keyguard.res.vault_main_no_suggested_items
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.util.HorizontalDivider
import com.artemchep.keyguard.wear.ui.WearListEmpty
import com.artemchep.keyguard.wear.ui.WearSectionHeader
import com.artemchep.keyguard.wear.ui.WearSectionHeaderEmptyBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearVaultListItem(
    modifier: Modifier = Modifier,
    item: VaultItem2,
    transformation: SurfaceTransformation? = null,
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
        WearListEmpty(
            modifier = modifier,
            text = stringResource(Res.string.items_empty_label),
            transformation = transformation,
        )
    }

    is VaultItem2.Section -> VaultListItemSection(modifier, item, transformation = transformation)
    is VaultItem2.Button -> VaultListItemButton(modifier, item)
    is VaultItem2.Item -> VaultListItemText(modifier, item, transformation = transformation)
}

@Composable
fun VaultListItemSection(
    modifier: Modifier = Modifier,
    item: VaultItem2.Section,
    transformation: SurfaceTransformation?,
) {
    val text = item.text?.let { textResource(it) }
    WearSectionHeader(
        title = text,
        modifier = modifier,
        emptyBehavior = WearSectionHeaderEmptyBehavior.Spacer4,
        transformation = transformation,
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
fun VaultListItemText(
    modifier: Modifier = Modifier,
    item: VaultItem2.Item,
    leading: (@Composable RowScope.(@Composable () -> Unit) -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    transformation: SurfaceTransformation? = null,
) {
    val localState by item.localStateFlow.collectAsStateWithLifecycle()

    val onClick = localState.selectableItemState.onClick
    // fallback to default
        ?: when (val action = item.action) {
            VaultItem2.Item.Action.None -> null
            is VaultItem2.Item.Action.Dropdown -> null
            is VaultItem2.Item.Action.Go -> action.onClick
        }.takeIf { localState.selectableItemState.can }
    val onLongClick = localState.selectableItemState.onLongClick
    val updatedOnClick by rememberUpdatedState(onClick)
    FilledTonalButton(
        modifier = modifier,
        enabled = updatedOnClick != null,
        onClick = {
            updatedOnClick?.invoke()
        },
        onLongClick = onLongClick,
        icon = {
            if (leading != null) {
                Row {
                    leading {
                        AccountListItemTextIcon(
                            modifier = Modifier,
                            item = item,
                        )
                    }
                }
            } else {
                AccountListItemTextIcon(
                    modifier = Modifier,
                    item = item,
                )
            }
        },
        label = {
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
        secondaryLabel = {
            item.text
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    // composable
                        Text(
                            text = it,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = if (item.source.type == DSecret.Type.SecureNote) 4 else 2,
                        )
                }
        },
        transformation = transformation,
    )
}
