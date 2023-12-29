package com.artemchep.keyguard.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.artemchep.keyguard.feature.home.vault.component.ObscureChar
import kotlin.math.min
import kotlin.math.roundToInt

private const val obscureValueLength = 8

@Composable
fun animatedConcealedText(
    text: AnnotatedString,
    concealed: Boolean,
): AnnotatedString {
    val progress by animateFloatAsState(
        targetValue = if (concealed) 0f else 1f,
    )

    val length = text.length
    val lengthOfRealValue = (length.toFloat() * progress)
        .roundToInt()
    val minLengthOfShownValue = ((1f - progress) * obscureValueLength)
        .roundToInt()
        .coerceAtLeast(min(length, obscureValueLength))
    return buildAnnotatedString {
        append(text.subSequence(0, lengthOfRealValue))

        // Pad text from the end
        val times = minLengthOfShownValue - lengthOfRealValue
        repeat(times) {
            append(ObscureChar)
        }
    }
}

fun concealedText(): String {
    return buildString {
        val times = obscureValueLength
        repeat(times) {
            append(ObscureChar)
        }
    }
}

fun colorizePassword(
    password: String,
    contentColor: Color,
) = colorizePassword(
    password = password,
    digitColor = colorizePasswordDigitColor(contentColor),
    symbolColor = colorizePasswordSymbolColor(contentColor),
)

fun colorizePasswordDigitColor(
    contentColor: Color,
) = colorizePasswordDigitColor(onDark = contentColor.luminance() > 0.5f)

private fun colorizePasswordDigitColor(onDark: Boolean) = run {
    val saturation: Float
    val lightness: Float
    if (onDark) {
        saturation = 0.52f // saturation
        lightness = 0.56f // value
    } else {
        saturation = 0.72f // saturation
        lightness = 0.62f // value
    }
    Color.hsl(
        hue = 210f,
        saturation = saturation,
        lightness = lightness,
    )
}

fun colorizePasswordSymbolColor(
    contentColor: Color,
) = colorizePasswordSymbolColor(onDark = contentColor.luminance() > 0.5f)

private fun colorizePasswordSymbolColor(onDark: Boolean) = run {
    val saturation: Float
    val lightness: Float
    if (onDark) {
        saturation = 0.52f // saturation
        lightness = 0.56f // value
    } else {
        saturation = 0.72f // saturation
        lightness = 0.62f // value
    }
    Color.hsl(
        hue = 0f,
        saturation = saturation,
        lightness = lightness,
    )
}

private fun colorizePassword(
    password: String,
    digitColor: Color = Color.Blue,
    symbolColor: Color = Color.Red,
) = buildAnnotatedString {
    password
        .forEach { char ->
            when {
                char.isSurrogate() -> append(char)
                char.isLetter() -> append(char)

                char.isDigit() -> {
                    withStyle(
                        style = SpanStyle(
                            color = digitColor,
                        ),
                    ) {
                        append(char)
                    }
                }

                else -> {
                    withStyle(
                        style = SpanStyle(
                            color = symbolColor,
                        ),
                    ) {
                        append(char)
                    }
                }
            }
        }
}
