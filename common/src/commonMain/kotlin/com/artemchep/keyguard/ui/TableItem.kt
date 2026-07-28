package com.artemchep.keyguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun TableRowItem(
    modifier: Modifier = Modifier,
    title: String,
    titleWeight: Float = 1f,
    text: String,
) {
    Row(
        modifier = modifier
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            modifier = Modifier
                .weight(titleWeight),
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        Text(
            modifier = Modifier
                .weight(1f),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
}
