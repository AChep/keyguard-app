package com.artemchep.keyguard.ui.skeleton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.DefaultEmphasisAlpha
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun SkeletonText(
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    emphasis: Float = DefaultEmphasisAlpha,
) {
    val contentColor = run {
        val color = style.color.takeIf { it.isSpecified } ?: LocalContentColor.current
        color.combineAlpha(DisabledEmphasisAlpha * emphasis)
    }
    val density = LocalDensity.current
    val height = with(density) {
        style.lineHeight.toDp()
    }
    Box(
        modifier
            .shimmer()
            .height(height)
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(contentColor),
    )
}
