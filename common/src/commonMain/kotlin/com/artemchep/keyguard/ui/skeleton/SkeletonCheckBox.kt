package com.artemchep.keyguard.ui.skeleton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun SkeletonCheckbox(
    modifier: Modifier = Modifier,
    clickable: Boolean = true,
) {
    val contentColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)
    Box(
        modifier
            .then(
                if (clickable) {
                    Modifier
                        .widthIn(min = 48.dp)
                        .heightIn(min = 48.dp)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier
                .shimmer()
                .size(24.dp)
                .clip(MaterialTheme.shapes.small)
                .background(contentColor),
        )
    }
}
