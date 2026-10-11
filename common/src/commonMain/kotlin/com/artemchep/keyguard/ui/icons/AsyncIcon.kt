package com.artemchep.keyguard.ui.icons

import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.rememberVectorPainterCustom
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

    val placeholderPainter = remember(highlightColor) {
        ColorPainter(highlightColor)
    }
    val errorPainter = rememberVectorPainterCustom(
        image = errorImageVector,
        tintColor = contentColor,
    )

    var isLoading by remember {
        mutableStateOf(false)
    }

    val painterData = remember(imageModel) {
        imageModel()
    }
    // The placeholder & error overload wraps the callbacks
    // into new lambdas on each recomposition, forcing Coil
    // to update its node every time.
    AsyncImage(
        modifier = modifier
            .then(
                if (isLoading) {
                    Modifier.shimmer()
                } else {
                    Modifier
                },
            ),
        model = painterData,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        transform = { state ->
            when (state) {
                is AsyncImagePainter.State.Loading -> state.copy(painter = placeholderPainter)
                is AsyncImagePainter.State.Error -> state.copy(painter = errorPainter)
                else -> state
            }
        },
        onState = { state ->
            isLoading = state is AsyncImagePainter.State.Loading
        },
    )
}
