package com.artemchep.keyguard.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevation
import com.artemchep.keyguard.ui.theme.combineAlpha

val DividerColor
    @Composable
    get() =
        LocalContentColor.current.combineAlpha(0.12f)

val DividerSize get() = 1.dp

@Composable
fun VerticalDivider(
    modifier: Modifier = Modifier,
    transparency: Boolean = true,
) {
    Divider(
        modifier = modifier
            .fillMaxHeight()
            .width(DividerSize),
        transparency = transparency,
    )
}

@Composable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    transparency: Boolean = true,
) = Divider(
    modifier = modifier
        .fillMaxWidth()
        .height(DividerSize),
    transparency = transparency,
)

@Composable
private fun Divider(
    modifier: Modifier = Modifier,
    transparency: Boolean,
) {
    val dividerColor = DividerColor
    val backgroundColor =
        if (transparency) {
            dividerColor
        } else {
            val elevation = LocalAbsoluteTonalElevation.current
            val background = MaterialTheme.colorScheme
                .surfaceColorAtElevation(elevation)
            dividerColor.compositeOver(background)
        }
    Box(
        modifier = modifier
            .background(backgroundColor),
    )
}
