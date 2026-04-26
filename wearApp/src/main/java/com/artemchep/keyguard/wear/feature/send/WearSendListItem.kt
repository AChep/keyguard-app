package com.artemchep.keyguard.wear.feature.send

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.empty_value
import com.artemchep.keyguard.res.items_empty_label
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.ui.WearListEmpty
import com.artemchep.keyguard.wear.ui.WearSectionHeader
import com.artemchep.keyguard.wear.ui.WearSectionHeaderEmptyBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearSendListItem(
    modifier: Modifier = Modifier,
    item: SendItem,
    transformation: SurfaceTransformation? = null,
) = when (item) {
    is SendItem.Section -> SendListItemSection(modifier, item, transformation = transformation)
    is SendItem.Item -> SendListItemText(modifier, item, transformation = transformation)
    is SendItem.NoItems -> {
        WearListEmpty(
            modifier = modifier,
            text = stringResource(Res.string.items_empty_label),
            transformation = transformation,
        )
    }
}

@Composable
fun SendListItemSection(
    modifier: Modifier = Modifier,
    item: SendItem.Section,
    transformation: SurfaceTransformation? = null,
) {
    WearSectionHeader(
        title = item.text,
        modifier = modifier,
        emptyBehavior = WearSectionHeaderEmptyBehavior.Spacer4,
        transformation = transformation,
    )
}

@Composable
fun SendListItemText(
    modifier: Modifier = Modifier,
    item: SendItem.Item,
    transformation: SurfaceTransformation? = null,
) {
    val localState by item.localStateFlow.collectAsStateWithLifecycle()

    val onClick = localState.selectableItemState.onClick
    // fallback to default
        ?: when (val action = item.action) {
            VaultItem2.Item.Action.None -> null
            is SendItem.Item.Action.Go -> action.onClick
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
                            maxLines = 2,
                        )
                }
        },
        transformation = transformation,
    )
}
