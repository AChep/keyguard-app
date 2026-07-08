package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.ui.WearListCard

@Composable
fun WearVaultViewTableItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Table,
    transformation: SurfaceTransformation? = null,
) {
    val title = item.title
    WearListCard(
        modifier = modifier
            .fillMaxWidth(),
        title = if (title != null) {
            {
                Text(
                    text = title,
                )
            }
        } else {
            null
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item.value?.let { value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                item.rows.forEach { row ->
                    WearVaultViewTableRow(row)
                }
            }
        },
        transformation = transformation,
    )
}

@Composable
private fun WearVaultViewTableRow(
    row: VaultViewItem.Table.Row,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(2.dp),
        )
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
