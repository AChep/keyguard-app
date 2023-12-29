package com.artemchep.keyguard.feature.search.sort.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.theme.selectedContainer

@Composable
fun SortItemComposable(
    modifier: Modifier = Modifier,
    checked: Boolean,
    icon: ImageVector?,
    title: String,
    onClick: (() -> Unit)?,
) {
    val updatedOnClick by rememberUpdatedState(onClick)

    val backgroundColor =
        if (checked) {
            MaterialTheme.colorScheme.selectedContainer
        } else Color.Unspecified
    Row(
        modifier = modifier
            .then(
                if (updatedOnClick != null) {
                    Modifier
                        .clickable {
                            updatedOnClick?.invoke()
                        }
                } else {
                    Modifier
                },
            )
            .background(backgroundColor)
            .minimumInteractiveComponentSize()
            .padding(
                horizontal = 8.dp,
                vertical = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(16.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                )
            }
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f),
        ) {
            FlatItemTextContent(
                title = {
                    Text(
                        text = title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                compact = true,
            )
        }
    }
}
