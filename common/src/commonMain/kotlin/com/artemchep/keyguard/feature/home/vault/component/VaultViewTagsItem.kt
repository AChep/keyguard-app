package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.theme.Dimens

@Composable
fun VaultViewTagsItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Tags,
) {
    FlowRow(
        modifier = modifier
            .padding(
                horizontal = Dimens.contentPadding,
            )
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
    ) {
        item.tags.forEach { tag ->
            key(tag) {
                TagItem(
                    modifier = Modifier,
                    tag = tag,
                    onClick = {
                        item.onClick(tag)
                    },
                )
            }
        }
    }
}

@Composable
private fun TagItem(
    modifier: Modifier = Modifier,
    tag: String,
    onClick: () -> Unit,
) {
    val backgroundColor = rememberFlatSurfaceExpressiveColor(
        expressive = true,
    )
    val updatedOnClick by rememberUpdatedState(onClick)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .drawBehind {
                drawRect(backgroundColor)
            }
            .clickable {
                updatedOnClick()
            }
            .padding(
                horizontal = Dimens.contentPadding,
                vertical = 8.dp,
            ),
    ) {
        Text(
            modifier = Modifier,
            text = tag,
            overflow = TextOverflow.Ellipsis,
            maxLines = 5,
        )
    }
}
