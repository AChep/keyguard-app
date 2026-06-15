package com.artemchep.keyguard.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.skeleton.defaultTextWidth
import com.artemchep.keyguard.ui.skeleton.defaultTitleWidth
import com.artemchep.keyguard.ui.skeleton.skeletonItemsPlacer

fun TransformingLazyColumnScope.skeletonItems(
    transformationSpec: TransformationSpec,
    titleWidthFraction: Float = defaultTitleWidth,
    textWidthFraction: Float? = defaultTextWidth,
    count: Int = 3,
) = skeletonItemsPlacer(
    count = count,
) { index, _ ->
    item("skeleton.$index") {
        WearSkeletonItem(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec),
            titleWidthFraction = titleWidthFraction,
            textWidthFraction = textWidthFraction,
            transformation = SurfaceTransformation(transformationSpec),
        )
    }
}

@Composable
fun WearSkeletonItem(
    modifier: Modifier = Modifier,
    titleWidthFraction: Float = defaultTitleWidth,
    textWidthFraction: Float? = defaultTextWidth,
    transformation: SurfaceTransformation? = null,
) {
    FilledTonalButton(
        modifier = modifier,
        label = {
            ProxyMaterial3Styles {
                SkeletonText(
                    modifier = Modifier
                        .fillMaxWidth(titleWidthFraction),
                )
            }
        },
        secondaryLabel = if (textWidthFraction != null) {
            // composable
            {
                ProxyMaterial3Styles {
                    SkeletonText(
                        modifier = Modifier
                            .fillMaxWidth(textWidthFraction),
                    )
                }
            }
        } else {
            null
        },
        onClick = {
            // Do nothing
        },
        enabled = false,
        transformation = transformation,
    )
}
