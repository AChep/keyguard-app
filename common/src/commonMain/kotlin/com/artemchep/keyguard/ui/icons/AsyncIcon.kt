package com.artemchep.keyguard.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun <T> AsyncIcon(
    imageModel: () -> T?,
    modifier: Modifier = Modifier,
    contentDescription: String?,
    errorImageVector: ImageVector,
) {
    val surfaceColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(LocalAbsoluteTonalElevation.current + 16.dp)
    val contentColor = LocalContentColor.current
    val highlightColor = contentColor
        .combineAlpha(MediumEmphasisAlpha)
        .compositeOver(surfaceColor)

    val painterData = remember(imageModel) {
        imageModel()
    }
    SubcomposeAsyncImage(
        modifier = modifier,
        model = painterData,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .shimmer()
                    .background(highlightColor),
            )
        },
        error = {
            Icon(
                modifier = Modifier
                    .align(Alignment.Center),
                imageVector = errorImageVector,
                contentDescription = null,
                tint = contentColor,
            )
        },
    )
}
