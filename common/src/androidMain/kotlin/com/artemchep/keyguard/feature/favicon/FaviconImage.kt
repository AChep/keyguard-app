package com.artemchep.keyguard.feature.favicon

import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.components.rememberImageComponent
import com.skydoves.landscapist.glide.GlideImage
import com.skydoves.landscapist.placeholder.shimmer.ShimmerPlugin

@Composable
actual fun FaviconImage(
    imageModel: () -> Any?,
    modifier: Modifier,
) {
    val surfaceColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(LocalAbsoluteTonalElevation.current + 16.dp)
    val contentColor = LocalContentColor.current
    val highlightColor = contentColor
        .combineAlpha(MediumEmphasisAlpha)
        .compositeOver(surfaceColor)
    GlideImage(
        modifier = modifier,
        imageModel = imageModel,
        imageOptions = ImageOptions(contentScale = ContentScale.Crop),
        component = rememberImageComponent {
            // Shows a shimmering effect when loading an image
            +ShimmerPlugin(
                baseColor = surfaceColor,
                highlightColor = highlightColor,
            )
        },
        failure = {
            Icon(
                modifier = Modifier
                    .align(Alignment.Center),
                imageVector = Icons.Outlined.KeyguardWebsite,
                contentDescription = null,
                tint = contentColor,
            )
        },
    )
}
