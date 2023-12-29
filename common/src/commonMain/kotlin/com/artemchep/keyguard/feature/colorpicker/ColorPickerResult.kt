package com.artemchep.keyguard.feature.colorpicker

import androidx.compose.ui.graphics.Color

sealed interface ColorPickerResult {
    data object Deny : ColorPickerResult

    data class Confirm(
        val color: Color,
    ) : ColorPickerResult
}
