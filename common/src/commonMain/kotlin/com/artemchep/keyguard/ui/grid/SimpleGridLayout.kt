package com.artemchep.keyguard.ui.grid

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.GridLayout
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.horizontalPaddingHalf
import kotlin.math.roundToInt

val preferredGridWidth = 220.dp

@Composable
fun SimpleGridLayout(
    modifier: Modifier = Modifier,
    mainAxisSpacing: Dp = Dimens.contentPadding,
    crossAxisSpacing: Dp = Dimens.contentPadding,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier,
    ) {
        val columns = (this@BoxWithConstraints.maxWidth / preferredGridWidth)
            .roundToInt()
            .coerceAtLeast(1)
        GridLayout(
            columns = columns,
            mainAxisSpacing = mainAxisSpacing,
            crossAxisSpacing = crossAxisSpacing,
            content = content,
        )
    }
}

