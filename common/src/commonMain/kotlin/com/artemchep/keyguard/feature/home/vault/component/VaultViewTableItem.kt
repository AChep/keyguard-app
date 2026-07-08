package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.util.HorizontalDivider

@Composable
fun VaultViewTableItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Table,
) {
    FlatDropdownSimpleExpressive(
        modifier = modifier,
        elevation = item.elevation,
        shapeState = item.shapeState,
        dropdown = item.dropdown,
        enabled = true,
        content = {
            if (item.title != null || item.value != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        modifier = Modifier
                            .weight(1f),
                        text = item.title.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        modifier = Modifier
                            .weight(1f),
                        text = item.value.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(
                    modifier = Modifier
                        .height(12.dp),
                )
            }

            item.rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        modifier = Modifier
                            .weight(1f),
                        text = row.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                    Text(
                        modifier = Modifier
                            .weight(1f),
                        text = row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                }
                if (index < item.rows.lastIndex) {
                    HorizontalDivider()
                }
            }
        },
    )
}
