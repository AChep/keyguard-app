package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.ui.surfaceTransformation

@Composable
fun WearVaultViewErrorItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Error,
    transformation: SurfaceTransformation? = null,
) {
    val contentColor = MaterialTheme.colorScheme.error
    Column(
        modifier = modifier
            .fillMaxWidth()
            .surfaceTransformation(transformation),
    ) {
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
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
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
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
        val blob = item.blob
        if (blob != null) Row(
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
                    text = blob,
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
        }
    }
}
