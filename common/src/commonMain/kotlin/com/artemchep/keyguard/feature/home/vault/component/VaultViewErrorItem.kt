package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.HighEmphasisAlpha
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.util.HorizontalDivider

@Composable
fun VaultViewErrorItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Error,
) {
    val contentColor = MaterialTheme.colorScheme.error
    val backgroundColor = MaterialTheme.colorScheme.errorContainer
        .combineAlpha(DisabledEmphasisAlpha)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 8.dp,
                vertical = 2.dp,
            )
            .padding(bottom = 24.dp),
        shape = MaterialTheme.shapes.large,
        color = backgroundColor,
    ) {
        Column(
            modifier = Modifier,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f),
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Icon(
                        modifier = Modifier
                            .padding(horizontal = 8.dp),
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = contentColor,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    ExpandedIfNotEmpty(
                        valueOrNull = item.message,
                    ) { message ->
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .padding(horizontal = 8.dp),
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current
                                .combineAlpha(MediumEmphasisAlpha),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val updatedOnRetry by rememberUpdatedState(item.onRetry)
                ExpandedIfNotEmptyForRow(
                    valueOrNull = updatedOnRetry,
                ) {
                    Row {
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                        IconButton(
                            onClick = {
                                updatedOnRetry?.invoke()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Sync,
                                contentDescription = null,
                            )
                        }
                    }
                }
                Spacer(
                    modifier = Modifier
                        .width(8.dp),
                )
            }
            if (item.blob != null) Row(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .height(IntrinsicSize.Min)
                    .background(
                        color = LocalContentColor.current
                            .combineAlpha(0.1f),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(
                    modifier = Modifier
                        .width(8.dp),
                )
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .background(
                            color = LocalContentColor.current
                                .combineAlpha(MediumEmphasisAlpha),
                        )
                        .fillMaxHeight(),
                )
                Spacer(
                    modifier = Modifier
                        .width(8.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f),
                ) {
                    Text(
                        modifier = modifier
                            .padding(
                                vertical = 8.dp,
                            ),
                        text = "BLOB",
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                    Text(
                        modifier = Modifier
                            .padding(),
                        text = item.blob,
                        maxLines = 3,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(
                        modifier = Modifier
                            .height(8.dp),
                    )
                }
                Spacer(
                    modifier = Modifier
                        .width(8.dp),
                )
                val updatedOnCopy by rememberUpdatedState(item.onCopyBlob)
                IconButton(
                    onClick = {
                        updatedOnCopy?.invoke()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                    )
                }
                Spacer(
                    modifier = Modifier
                        .width(8.dp),
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    modifier = Modifier
                        .size(14.dp),
                    imageVector = Icons.Outlined.AccessTime,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    modifier = Modifier,
                    text = item.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current
                        .combineAlpha(HighEmphasisAlpha),
                )
            }
        }
    }
}
