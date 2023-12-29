package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun GridLayout(
    modifier: Modifier = Modifier,
    columns: Int = 1,
    mainAxisSpacing: Dp = 0.dp,
    crossAxisSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) = Layout(
    modifier = modifier,
    content = content,
) { measurables, constraints ->
    val mainAxisSpacingPx = mainAxisSpacing.roundToPx()
    val crossAxisSpacingPx = crossAxisSpacing.roundToPx()

    val itemWidth = (constraints.maxWidth - crossAxisSpacingPx * (columns - 1)) / columns
    val itemConstraints = Constraints(
        minWidth = itemWidth,
        maxWidth = itemWidth,
    )

    val placeables = measurables
        .asSequence()
        .windowed(columns, columns, true) { it }
        .flatMap { group ->
            val height = group
                .maxOf { it.minIntrinsicHeight(itemWidth) }
            val groupConstraints = itemConstraints
                .copy(minHeight = height)
            // Measure each of the element of the group separately,
            // so we it has similar height.
            group
                .map { it.measure(groupConstraints) }
        }
        .toList()
    var height = 0
    var heightLocalMax = 0
    for (i in placeables.indices) {
        // Reset the max height upon
        // entering the new group
        if (i.rem(columns) == 0) {
            height += heightLocalMax
            heightLocalMax = 0
        }
        val h = placeables[i].height + mainAxisSpacingPx
        if (h > heightLocalMax) {
            heightLocalMax = h
        }
    }
    height += heightLocalMax
    // Set the size of the layout as big as it can
    layout(constraints.maxWidth, height) {
        var y = 0
        var yLocalMax = 0
        placeables.forEachIndexed { i, placeable ->
            if (i.rem(columns) == 0) {
                y += yLocalMax
                yLocalMax = 0
            }
            val x = i.rem(columns) * (itemWidth + crossAxisSpacingPx)
            placeable.place(x, y)

            val h = placeables[i].height + mainAxisSpacingPx
            if (h > yLocalMax) {
                yLocalMax = h
            }
        }
    }
}
