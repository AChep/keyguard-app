package com.artemchep.keyguard.ui.skeleton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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
fun SkeletonTextField(
    modifier: Modifier = Modifier,
) {
    val contentColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)
    Box(
        modifier
            .shimmer()
            .height(64.dp)
            .clip(MaterialTheme.shapes.large)
            .background(contentColor),
    )
}
