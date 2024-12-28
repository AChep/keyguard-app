package com.artemchep.keyguard.ui.tooltip

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.verticalPaddingHalf

@Composable
fun <T : Any> Tooltip(
    modifier: Modifier = Modifier,
    valueOrNull: T?,
    tooltip: @Composable BoxScope.(T) -> Unit,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        modifier = modifier,
        tooltip = {
            if (valueOrNull != null) {
                val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                val contentColor = contentColorFor(backgroundColor)
                Box(
                    modifier = Modifier
                        .background(
                            color = backgroundColor,
                            shape = MaterialTheme.shapes.medium,
                        )
                        .padding(
                            horizontal = Dimens.horizontalPadding,
                            vertical = Dimens.verticalPaddingHalf,
                        )
                        .widthIn(
                            max = 320.dp,
                        ),
                ) {
                    CompositionLocalProvider(
                        LocalSurfaceColor provides backgroundColor,
                        LocalContentColor provides contentColor,
                        LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                    ) {
                        tooltip(valueOrNull)
                    }
                }
            }
        },
        content = content,
    )
}
