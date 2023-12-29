package com.artemchep.keyguard.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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

@Composable
fun AhContainer(
    score: Float,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(Float) -> Unit,
) {
    val progress by animateFloatAsState(targetValue = score)
    val progressColor = progress.pow(0.4f)
    val contentColor = run {
        val primary = MaterialTheme.colorScheme.onSecondaryContainer
        val error = MaterialTheme.colorScheme.onErrorContainer
        primary.copy(alpha = progressColor).compositeOver(error)
            .combineAlpha(LocalContentColor.current.alpha)
    }
    val backgroundColor = run {
        val primary = MaterialTheme.colorScheme.secondaryContainer
        val error = MaterialTheme.colorScheme.errorContainer
        primary.copy(alpha = progressColor).compositeOver(error)
    }
    AhLayout(
        modifier = modifier,
        contentColor = contentColor,
        backgroundColor = backgroundColor,
    ) {
        content(progress)
    }
}

@Composable
fun AhLayout(
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
    backgroundColor: Color,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .background(backgroundColor.combineAlpha(LocalContentColor.current.alpha), shape)
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
