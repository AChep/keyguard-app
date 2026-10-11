package com.artemchep.keyguard.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlin.math.pow

@Composable
fun Ah(
    score: Float,
    text: String,
    modifier: Modifier = Modifier,
) {
    AhContainer(
        score = score,
        modifier = modifier,
    ) {
        Text(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .animateContentSize(),
            text = text,
        )
    }
}

@Composable
fun Ah(
    score: Float,
    text: AnnotatedString,
    modifier: Modifier = Modifier,
) {
    AhContainer(
        score = score,
        modifier = modifier,
    ) {
        Text(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .animateContentSize(),
            text = text,
        )
    }
}

/**
 * A badge colored by the [score], from the error colors
 * at 0 to the secondary colors at 1.
 *
 * The [content] receives the animated progress as a
 * provider; read it only in the layout or draw phase.
 */
@Composable
fun AhContainer(
    score: Float,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(progress: () -> Float) -> Unit,
) {
    val progressState = animateFloatAsState(targetValue = score)
    // The content color follows the target score, so it changes
    // together with the content. The background follows the animated
    // progress in the draw phase, so animating does not recompose.
    val contentColor = run {
        val primary = MaterialTheme.colorScheme.onSecondaryContainer
        val error = MaterialTheme.colorScheme.onErrorContainer
        primary.copy(alpha = score.pow(0.4f)).compositeOver(error)
            .combineAlpha(LocalContentColor.current.alpha)
    }
    val backgroundPrimary = MaterialTheme.colorScheme.secondaryContainer
    val backgroundError = MaterialTheme.colorScheme.errorContainer
    AhLayout(
        modifier = modifier,
        contentColor = contentColor,
        backgroundColor = {
            val progressColor = progressState.value.pow(0.4f)
            backgroundPrimary.copy(alpha = progressColor).compositeOver(backgroundError)
        },
    ) {
        content { progressState.value }
    }
}

@Composable
fun AhLayout(
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
    backgroundColor: Color,
    content: @Composable RowScope.() -> Unit,
) = AhLayout(
    modifier = modifier,
    contentColor = contentColor,
    backgroundColor = { backgroundColor },
    content = content,
)

@Composable
private fun AhLayout(
    modifier: Modifier = Modifier,
    contentColor: Color,
    backgroundColor: ColorProducer,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    val backgroundAlpha = LocalContentColor.current.alpha
    Row(
        modifier = modifier
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                onDrawBehind {
                    val color = backgroundColor().combineAlpha(backgroundAlpha)
                    drawOutline(outline, color)
                }
            }
            .padding(
                start = 4.dp,
                top = 4.dp,
                bottom = 4.dp,
                end = 4.dp,
            )
            .widthIn(min = 36.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val textStyle = MaterialTheme.typography.labelMedium
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
            LocalTextStyle provides textStyle,
        ) {
            content()
        }
    }
}
