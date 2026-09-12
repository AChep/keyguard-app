package com.artemchep.keyguard.feature.colorpicker

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlin.test.Test
import kotlin.test.assertEquals

class ColorPickerSelectionTest {
    @Test
    fun savedPaletteColorsKeepTheirSelection() {
        val length = 64
        repeat(length) { index ->
            val colors = generateAccentColors(hue = 360f * index / length)
            for (color in listOf(colors.light, colors.dark)) {
                val restored = Color(color.toArgb())
                assertEquals(index, colorPickerSelectedIndex(restored, length), "Swatch $index")
            }
        }
    }

    @Test
    fun hueNearFullCircleSelectsRed() {
        assertEquals(0, colorPickerSelectedIndex(Color.hsv(359.9f, 0.5f, 0.8f), 64))
    }
}
