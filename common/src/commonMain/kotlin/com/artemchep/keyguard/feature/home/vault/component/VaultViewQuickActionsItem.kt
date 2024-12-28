package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun VaultViewQuickActionsItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.QuickActions,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(
            modifier = Modifier
                .width(4.dp),
        )
        item.actions.forEach { i ->
            HorizontalContextItem(
                modifier = Modifier
                    .widthIn(max = DropdownMinWidth),
                item = i,
            )
        }
        Spacer(
            modifier = Modifier
                .width(4.dp),
        )
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

@Composable
private fun HorizontalFlatActionItem(
    modifier: Modifier = Modifier,
    item: FlatItemAction,
) {
    val updatedOnClick by rememberUpdatedState(item.onClick)
    val backgroundModifier = run {
        val bg = Color.Transparent
        val fg = MaterialTheme.colorScheme.surfaceColorAtElevationSemi(1.dp)
        Modifier
            .background(fg.compositeOver(bg))
    }
    Row(
        modifier = modifier
            // Normal items have a small vertical padding,
            // so add it here as well for consistency.
            .padding(
                vertical = 2.dp,
            )
            .clip(RoundedCornerShape(16.dp))
            .then(backgroundModifier)
            .clickable {
                updatedOnClick?.invoke()
            }
            .minimumInteractiveComponentSize()
            .padding(
                horizontal = 8.dp,
                vertical = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalFlatActionContent(
            action = item,
            compact = true,
        )
    }
}

@Composable
private fun RowScope.HorizontalFlatActionContent(
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
        if ((action.icon != null && action.icon != Icons.Stub) || action.leading != null) {
            Box(
                modifier = Modifier
                    .size(24.dp),
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
                    .width(16.dp),
            )
        }
        Column(
            modifier = Modifier,
            verticalArrangement = Arrangement.Center,
        ) {
            FlatItemTextContent(
                title = {
                    Text(
                        text = textResource(action.title),
                        maxLines = 1,
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
