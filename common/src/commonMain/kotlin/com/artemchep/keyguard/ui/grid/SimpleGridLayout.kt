package com.artemchep.keyguard.ui.grid

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.GridLayout
import com.artemchep.keyguard.ui.theme.Dimens
import kotlin.math.floor
import kotlin.math.roundToInt

val preferredGridWidth = 220.dp

@Composable
fun SimpleGridLayout(
    modifier: Modifier = Modifier,
    mainAxisSpacing: Dp = Dimens.contentPadding,
    crossAxisSpacing: Dp = Dimens.contentPadding,
    minCellWidth: Dp = Dp.Infinity,
    preferredCellWidth: Dp = preferredGridWidth,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier,
    ) {
        val columns = calculateSimpleGridLayoutColumnsCount(
            maxWidth = this.maxWidth,
            minCellWidth = minCellWidth,
            preferredCellWidth = preferredCellWidth,
        )
        GridLayout(
            columns = columns,
            mainAxisSpacing = mainAxisSpacing,
            crossAxisSpacing = crossAxisSpacing,
            content = content,
        )
    }
}

fun calculateSimpleGridLayoutColumnsCount(
    maxWidth: Dp,
    minCellWidth: Dp = Dp.Infinity,
    preferredCellWidth: Dp = preferredGridWidth,
): Int {
    val estimatedColumnCount = (maxWidth / preferredCellWidth)
        .roundToInt()
    val estimatedCellWidth = (maxWidth / estimatedColumnCount)
        .coerceAtLeast(minCellWidth)
    return (maxWidth / estimatedCellWidth)
        .let(::floor)
        .toInt()
        .coerceAtLeast(1)
}
