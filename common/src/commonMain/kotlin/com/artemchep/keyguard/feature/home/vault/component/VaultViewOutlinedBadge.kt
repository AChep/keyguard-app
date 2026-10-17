package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.util.DividerColor

/**
 * A short outlined label shown at the end of an item's
 * main text, for example the URI match type.
 */
@Composable
fun VaultViewOutlinedBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier
            .widthIn(max = 128.dp)
            .border(
                Dp.Hairline,
                DividerColor,
                MaterialTheme.shapes.small,
            )
            .padding(
                horizontal = 8.dp,
                vertical = 4.dp,
            ),
        text = text,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.End,
        maxLines = 2,
    )
}
