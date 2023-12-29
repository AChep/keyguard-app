package com.artemchep.keyguard.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.shimmer.shimmer

@Composable
fun FlatItemSkeleton(
    avatar: Boolean = false,
    fill: Boolean = false,
    titleWidthFraction: Float = 0.45f,
    textWidthFraction: Float = 0.33f,
) {
    val contentColor = LocalContentColor.current.copy(alpha = DisabledEmphasisAlpha)
    FlatItemLayout(
        modifier = Modifier
            .shimmer(),
        backgroundColor = contentColor
            .takeIf { fill }
            ?.copy(
                alpha = 0.08f,
            )
            ?: Color.Unspecified,
        leading = if (avatar) {
            // composable
            {
                Avatar {
                }
            }
        } else {
            null
        },
        content = {
            Box(
                Modifier
                    .height(18.dp)
                    .fillMaxWidth(titleWidthFraction)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor),
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .height(14.dp)
                    .fillMaxWidth(textWidthFraction)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor),
            )
        },
    )
}
