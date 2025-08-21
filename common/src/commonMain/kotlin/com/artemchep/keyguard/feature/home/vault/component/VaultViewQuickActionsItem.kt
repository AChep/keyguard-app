package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.collections.immutable.ImmutableList

@Composable
fun VaultViewQuickActionsItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.QuickActions,
) {
    HorizontalContextItems(
        modifier = modifier,
        items = item.actions,
    )
}

@Composable
fun HorizontalContextItems(
    modifier: Modifier = Modifier,
    items: ImmutableList<ContextItem>,
) {
    FlowRow(
        modifier = modifier
            .padding(
                start = Dimens.contentPadding,
                end = Dimens.contentPadding,
                bottom = 24.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { i ->
            HorizontalContextItem(
                modifier = Modifier
                    .widthIn(max = DropdownMinWidth),
                item = i,
            )
        }
    }
}

@Composable
private fun HorizontalContextItem(
    modifier: Modifier = Modifier,
    item: ContextItem,
) = when (item) {
    is ContextItem.Section -> {
        Section(
            modifier = modifier,
            text = item.title,
        )
    }

    is ContextItem.Custom -> {
        Column(
            modifier = modifier,
        ) {
            item.content()
        }
    }

    is FlatItemAction -> {
        HorizontalFlatActionItem(
            modifier = modifier,
            item = item,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HorizontalFlatActionItem(
    modifier: Modifier = Modifier,
    item: FlatItemAction,
) {
    val updatedOnClick by rememberUpdatedState(item.onClick)
    Button(
        modifier = modifier,
        onClick = {
            updatedOnClick?.invoke()
        },
        enabled = updatedOnClick != null,
    ) {
        HorizontalFlatActionContent(
            action = item,
        )
    }
}

@Composable
private fun RowScope.HorizontalFlatActionContent(
    action: FlatItemAction,
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
        if ((action.icon != null && action.icon != Icons.Stub) || action.leading != null) {
            Box(
                modifier = Modifier
                    .size(ButtonDefaults.IconSize),
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
                    .width(ButtonDefaults.IconSpacing),
            )
        }
        Column(
            modifier = Modifier,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = textResource(action.title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (action.text != null) {
                Text(
                    text = textResource(action.text),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
