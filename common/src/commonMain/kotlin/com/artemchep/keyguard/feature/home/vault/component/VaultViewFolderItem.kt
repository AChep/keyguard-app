package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.theme.combineAlpha

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultViewFolderItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Folder,
) {
    FlatItemSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        leading = {
            Icon(
                Icons.Outlined.Folder,
                null,
            )
        },
        trailing = {
            ChevronIcon()
        },
        title = {
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
            ) {
                val paddingModifier = Modifier
                    .padding(
                        horizontal = 8.dp,
                        vertical = 4.dp,
                    )
                item.nodes.forEachIndexed { index, s ->
                    if (item.nodes.size > 1 && index != item.nodes.lastIndex) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.CenterVertically),
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 1.dp,
                        ) {
                            val updatedOnClick by rememberUpdatedState(s.onClick)
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        updatedOnClick()
                                    }
                                    .then(paddingModifier),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    modifier = Modifier
                                        .weight(1f, fill = false),
                                    text = s.name,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 5,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    null,
                                    modifier = Modifier
                                        .size(18.dp),
                                    tint = LocalContentColor.current
                                        .combineAlpha(DisabledEmphasisAlpha),
                                )
                            }
                        }
                    } else if (item.nodes.size == 1) {
                        Text(
                            modifier = Modifier
                                .align(Alignment.CenterVertically),
                            text = s.name,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 5,
                        )
                    } else {
                        Text(
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .then(paddingModifier),
                            text = s.name,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 5,
                        )
                    }
                }
            }
        },
        onClick = item.onClick,
    )
}
