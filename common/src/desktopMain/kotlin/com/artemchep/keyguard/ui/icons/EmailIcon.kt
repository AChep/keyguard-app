package com.artemchep.keyguard.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
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
import com.artemchep.keyguard.feature.favicon.GravatarUrl
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.combineAlpha
import io.kamel.image.KamelImage
import io.kamel.image.asyncPainterResource

@Composable
actual fun EmailIcon(
    modifier: Modifier,
    gravatarUrl: GravatarUrl?,
) {
    val surfaceColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(LocalAbsoluteTonalElevation.current + 16.dp)
    val contentColor = LocalContentColor.current
    val highlightColor = contentColor
        .combineAlpha(MediumEmphasisAlpha)
        .compositeOver(surfaceColor)

    val painterResource =
        asyncPainterResource(data = gravatarUrl?.url ?: Any())
    KamelImage(
        modifier = modifier,
        contentScale = ContentScale.Crop,
        resource = painterResource,
        contentDescription = "Email",
        onLoading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .shimmer()
                    .background(highlightColor),
            )
        },
        onFailure = {
            Icon(
                modifier = Modifier
                    .align(Alignment.Center),
                imageVector = Icons.Outlined.Email,
                contentDescription = null,
                tint = contentColor,
            )
        },
    )
}
