package com.artemchep.keyguard.ui.skeleton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun SkeletonSlider(
    modifier: Modifier = Modifier,
) {
    val contentColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)
    Box(
        modifier = modifier
            .padding(
                vertical = 14.dp,
            )
            .heightIn(min = 20.dp)
            .widthIn(min = 30.dp)
            .shimmer()
            .clip(MaterialTheme.shapes.medium)
            .background(contentColor),
    )
}
