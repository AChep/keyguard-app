package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter

/**
 * Create a painter of the given [ImageVector] tinted with the [tintColor]. The
 * instance stays the same when the [tintColor] changes, so holders that keep a
 * painter, such as an image loader's error slot, still draw the latest tint.
 *
 * @param [image] ImageVector used to create a vector graphic
 */
@Composable
fun rememberVectorPainterCustom(
    image: ImageVector,
    tintColor: Color = Color.Unspecified,
): Painter {
    val vectorPainter = rememberVectorPainter(image)
    val colorFilter = remember(tintColor, image.tintBlendMode) {
        tintColor.takeIf { it.isSpecified }
            ?.let { ColorFilter.tint(it, image.tintBlendMode) }
    }
    val painter = remember(vectorPainter) {
        TintedPainter(vectorPainter)
    }
    SideEffect {
        painter.colorFilter = colorFilter
    }
    return painter
}

private class TintedPainter(
    private val painter: Painter,
) : Painter() {
    var colorFilter: ColorFilter? by mutableStateOf(null)

    override val intrinsicSize: Size
        get() = painter.intrinsicSize

    override fun DrawScope.onDraw() {
        with(painter) {
            draw(size, colorFilter = colorFilter)
        }
    }
}
