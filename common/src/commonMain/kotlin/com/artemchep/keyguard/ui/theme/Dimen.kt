package com.artemchep.keyguard.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val Dimens
    @Composable
    @ReadOnlyComposable
    get() = LocalDimens.current

internal val LocalDimens = staticCompositionLocalOf {
    Dimen.normal()
}

data class Dimen(
    val verticalPadding: Dp,
    val horizontalPadding: Dp,
    val topPaddingCaption: Dp,
    val buttonIconPadding: Dp = 12.dp,
) {
    companion object {
        fun normal() = Dimen(
            verticalPadding = 16.dp,
            horizontalPadding = 16.dp,
            topPaddingCaption = 8.dp,
        )

        fun compact() = Dimen(
            verticalPadding = 16.dp,
            horizontalPadding = 8.dp,
            topPaddingCaption = 8.dp,
        )
    }
}

val Dimen.horizontalPaddingHalf get() = horizontalPadding / 2

val Dimen.horizontalPaddingFourth get() = horizontalPadding / 4
