package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.warning
import com.artemchep.keyguard.ui.theme.warningContainer
import com.artemchep.keyguard.ui.util.DividerColor

@Composable
fun VaultViewUriItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Uri,
) {
    FlatDropdown(
        modifier = modifier,
        leading = {
            item.icon()
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = item.title,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 5,
                    )
                },
                text = if (item.text != null) {
                    // composable
                    {
                        Text(
                            text = item.text,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 5,
                        )
                    }
                } else {
                    null
                },
            )
            ExpandedIfNotEmpty(
                item.warningTitle,
            ) { warningTitle ->
                val contentColor = MaterialTheme.colorScheme.warning
                val backgroundColor = MaterialTheme.colorScheme.warningContainer
                    .combineAlpha(DisabledEmphasisAlpha)
                Row(
                    modifier = Modifier
                        .padding(
                            top = 4.dp,
                        )
                        .background(
                            backgroundColor,
                            MaterialTheme.shapes.small,
                        )
                        .padding(
                            start = 4.dp,
                            top = 4.dp,
                            bottom = 4.dp,
                            end = 4.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        modifier = Modifier
                            .size(14.dp),
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = contentColor,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        modifier = Modifier,
                        text = warningTitle,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        trailing = {
            ExpandedIfNotEmptyForRow(
                item.matchTypeTitle,
            ) { matchTypeTitle ->
                Text(
                    modifier = Modifier
                        .widthIn(max = 128.dp)
                        .padding(
                            top = 8.dp,
                            bottom = 8.dp,
                        )
                        .border(
                            Dp.Hairline,
                            DividerColor,
                            MaterialTheme.shapes.small,
                        )
                        .padding(
                            horizontal = 8.dp,
                            vertical = 4.dp,
                        ),
                    text = matchTypeTitle,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                )
            }
        },
        dropdown = item.dropdown,
    )
}
