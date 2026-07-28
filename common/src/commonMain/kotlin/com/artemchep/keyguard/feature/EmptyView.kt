package com.artemchep.keyguard.feature

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

private const val PixelEmptyArtworkGridSize = 48

private const val PixelEmptyArtworkDurationMillis = 2200

@Composable
fun EmptySearchView(
    modifier: Modifier = Modifier,
    text: @Composable () -> Unit = {
        DefaultEmptyViewText()
    },
) {
    EmptyView(
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Outlined.SearchOff,
                contentDescription = null,
            )
        },
        text = text,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmptyView(
    modifier: Modifier = Modifier,
    largeArtwork: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit = {
        DefaultEmptyViewText()
    },
) {
    Box(
        modifier = modifier
            .padding(top = 24.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(
                    vertical = Dimens.verticalPadding,
                    horizontal = 32.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (largeArtwork && !CurrentPlatform.hasWatch()) {
                PixelEmptyArtwork(
                    modifier = Modifier
                        .width(172.dp)
                        .height(172.dp),
                )
                Spacer(
                    modifier = Modifier
                        .height(Dimens.verticalPadding),
                )
            }
            Row(
                modifier = Modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    LocalTextStyle provides MaterialTheme.typography.labelLarge,
                ) {
                    if (icon != null) {
                        icon()
                        Spacer(
                            modifier = Modifier
                                .width(Dimens.buttonIconPadding),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .widthIn(max = 196.dp),
                    ) {
                        text()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PixelEmptyArtwork(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "Empty view pixel artwork")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = PixelEmptyArtworkDurationMillis,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Empty view pixel artwork frame",
    )
    val phase = progress.toDouble() * PI * 2.0
    val bobOffsetY = (sin(phase) * 0.0).toFloat()
    val blink = (1f - abs(progress - 0.62f) / 0.055f)
        .coerceIn(0f, 1f)

    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = modifier,
    ) {
        val gridSize = PixelEmptyArtworkGridSize
        val cell = floor(minOf(size.width, size.height) / gridSize)
            .coerceAtLeast(1f)
        val artworkSize = cell * gridSize
        val origin = Offset(
            x = floor((size.width - artworkSize) / 2f),
            y = floor((size.height - artworkSize) / 2f),
        )
        val pixelSize = Size(cell, cell)

        fun drawPixel(
            x: Int,
            y: Int,
            color: Color = contentColor,
            alpha: Float,
            offsetY: Float = 0f,
        ) {
            if (x !in 0 until gridSize || y !in 0 until gridSize) {
                return
            }

            drawRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(
                    x = origin.x + x * cell,
                    y = origin.y + (y + offsetY) * cell,
                ),
                size = pixelSize,
            )
        }

        fun drawPixelRect(
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            color: Color = contentColor,
            alpha: Float,
            offsetY: Float = 0f,
        ) {
            for (x in left until left + width) {
                for (y in top until top + height) {
                    drawPixel(
                        x = x,
                        y = y,
                        color = color,
                        alpha = alpha,
                        offsetY = offsetY,
                    )
                }
            }
        }

        PixelEmptyArtworkBackgroundPixels.forEachIndexed { index, (x, y) ->
            val pulse = ((sin(phase + index * 0.9) + 1.0) * 0.5).toFloat()
            drawPixel(
                x = x,
                y = y,
                alpha = 0.06f + pulse * 0.14f,
            )
        }

        val openEyeAlpha = 0.92f * (1f - blink)
        drawPixelRect(
            left = 16,
            top = 19,
            width = 6,
            height = 4,
            color = backgroundColor,
            alpha = openEyeAlpha,
            offsetY = bobOffsetY,
        )
        drawPixelRect(
            left = 29,
            top = 17,
            width = 6,
            height = 4,
            color = backgroundColor,
            alpha = openEyeAlpha,
            offsetY = bobOffsetY,
        )
        drawPixelRect(
            left = 18,
            top = 20,
            width = 2,
            height = 2,
            alpha = 0.84f * (1f - blink),
            offsetY = bobOffsetY,
        )
        drawPixelRect(
            left = 31,
            top = 18,
            width = 2,
            height = 2,
            alpha = 0.84f * (1f - blink),
            offsetY = bobOffsetY,
        )
        drawPixelRect(
            left = 17,
            top = 20,
            width = 6,
            height = 1,
            color = backgroundColor,
            alpha = 0.88f * blink,
            offsetY = bobOffsetY,
        )
        drawPixelRect(
            left = 29,
            top = 19,
            width = 6,
            height = 1,
            color = backgroundColor,
            alpha = 0.88f * blink,
            offsetY = bobOffsetY,
        )

        PixelEmptyArtworkSadMouthPixels.forEach { (x, y) ->
            drawPixel(
                x = x,
                y = y,
                alpha = 0.84f,
                offsetY = bobOffsetY,
            )
        }
    }
}

@Composable
private fun DefaultEmptyViewText(
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = stringResource(Res.string.items_empty_label),
    )
}

private val PixelEmptyArtworkBackgroundPixels = listOf(
    8 to 8,
    16 to 6,
    28 to 8,
    38 to 10,
    6 to 20,
    42 to 20,
    8 to 34,
    38 to 34,
    14 to 40,
    32 to 40,
)

private val PixelEmptyArtworkSadMouthPixels = listOf(
    22 to 27,
    23 to 26,
    24 to 26,
    25 to 26,
    26 to 26,
    27 to 27,
)
