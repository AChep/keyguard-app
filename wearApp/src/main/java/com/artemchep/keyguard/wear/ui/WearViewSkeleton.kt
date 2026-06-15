package com.artemchep.keyguard.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.shimmer.shimmer

/**
 * Loading skeleton for detail/view screens (vault view, send view, etc.).
 *
 * Shows shimmer placeholders that mimic the fully loaded layout, giving
 * the user a sense of the final screen structure while data is loading.
 */
fun TransformingLazyColumnScope.wearViewLoadingSkeletonItems(
    transformationSpec: TransformationSpec,
) {
    for (i in 0..1) {
        item("skeleton.flat.$i") {
            val contentColor =
                LocalContentColor.current.copy(alpha = DisabledEmphasisAlpha)
            FlatItemLayout(
                modifier = Modifier
                    .shimmer()
                    .transformedHeight(this, transformationSpec),
                backgroundColor = contentColor.copy(alpha = 0.08f),
                content = {
                    Box(
                        Modifier
                            .height(13.dp)
                            .fillMaxWidth(0.15f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(contentColor.copy(alpha = 0.2f)),
                    )
                    Box(
                        Modifier
                            .padding(top = 4.dp)
                            .height(18.dp)
                            .fillMaxWidth(0.38f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(contentColor),
                    )
                },
            )
        }
    }
    item("skeleton.trailing") {
        val contentColor =
            LocalContentColor.current.copy(alpha = DisabledEmphasisAlpha)
        @Composable
        fun ShimmerBar() {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .shimmer()
                        .height(10.dp)
                        .fillMaxWidth(0.38f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(contentColor.copy(alpha = 0.23f)),
                )
            }
        }
        ShimmerBar()
        ShimmerBar()
    }
}
