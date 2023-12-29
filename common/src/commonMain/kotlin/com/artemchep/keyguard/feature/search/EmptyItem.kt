package com.artemchep.keyguard.feature.search

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun EmptyItem(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier
            .padding(
                horizontal = Dimens.horizontalPadding,
                vertical = 8.dp,
            ),
        text = text,
        color = LocalContentColor.current
            .combineAlpha(alpha = MediumEmphasisAlpha),
        style = MaterialTheme.typography.labelMedium,
    )
}
