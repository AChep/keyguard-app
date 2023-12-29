package com.artemchep.keyguard.ui.skeleton

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun SkeletonItem(
    avatar: Boolean = false,
    titleWidthFraction: Float = 0.45f,
    textWidthFraction: Float? = 0.33f,
) {
    FlatItem(
        leading = if (avatar) {
            // composable
            {
                Avatar(
                    modifier = Modifier
                        .shimmer(),
                    color = LocalContentColor.current
                        .combineAlpha(DisabledEmphasisAlpha),
                ) {
                }
            }
        } else {
            null
        },
        title = {
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(titleWidthFraction),
            )
        },
        text = if (textWidthFraction != null) {
            // composable
            {
                SkeletonText(
                    modifier = Modifier
                        .fillMaxWidth(textWidthFraction),
                )
            }
        } else {
            null
        },
        enabled = true,
    )
}
