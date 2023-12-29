package com.artemchep.keyguard.ui.skeleton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun SkeletonItemPilled(
    modifier: Modifier = Modifier,
    chevron: Boolean = false,
) {
    val contentColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)
    FlatItemLayout(
        modifier = modifier
            .shimmer()
            .padding(top = 1.dp),
        leading = {
            Box(
                Modifier
                    .height(18.dp)
                    .width(44.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor.copy(alpha = 0.2f)),
            )
        },
        content = {
            Row(
                Modifier
                    .fillMaxWidth(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .height(18.dp)
                        .fillMaxWidth(0.4f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(contentColor),
                )
                if (chevron) {
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(contentColor.copy(alpha = 0.2f)),
                    )
                }
            }
        },
    )
}
