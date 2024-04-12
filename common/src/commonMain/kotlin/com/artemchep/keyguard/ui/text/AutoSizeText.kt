package com.artemchep.keyguard.ui.text

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.InternalFoundationTextApi
import androidx.compose.foundation.text.TextDelegate
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.artemchep.keyguard.ui.text.SuggestedFontSizesStatus.Companion.rememberSuggestedFontSizesStatus
import kotlin.math.min
import kotlin.reflect.KProperty

// Code is based on the
// https://gist.github.com/inidamleader/b594d35362ebcf3cedf81055df519300
// and is under "no licence for this code, you can use it without problem" :)
//
// Thanks!

/**
 * Composable function that automatically adjusts the text size to fit within given constraints, considering the ratio of line spacing to text size.
 *
 * Features:
 *  1. Best performance: Utilizes a dichotomous binary search algorithm for swift and optimal text size determination without unnecessary iterations.
 *  2. Alignment support: Supports six possible alignment values via the Alignment interface.
 *  3. Material Design 3 support.
 *  4. Font scaling support: User-initiated font scaling doesn't affect the visual rendering output.
 *  5. Multiline Support with maxLines Parameter.
 *
 * @param text the text to be displayed
 * @param modifier the [Modifier] to be applied to this layout node
 * @param color [Color] to apply to the text. If [Color.Unspecified], and [style] has no color set,
 * this will be [LocalContentColor].
 * @param suggestedFontSizes The suggested font sizes to choose from (Should be sorted from smallest to largest, not empty and contains only sp text unit).
 * @param suggestedFontSizesStatus Whether or not suggestedFontSizes is valid: not empty - contains oly sp text unit - sorted.
 * You can check validity by invoking [List<TextUnit>.suggestedFontSizesStatus]
 * @param stepGranularityTextSize The step size for adjusting the text size. this parameter is ignored if [suggestedFontSizes] is specified and [suggestedFontSizesStatus] is [SuggestedFontSizesStatus.VALID].
 * @param minTextSize The minimum text size allowed. this parameter is ignored if [suggestedFontSizes] is specified or [suggestedFontSizesStatus] is [SuggestedFontSizesStatus.VALID].
 * @param maxTextSize The maximum text size allowed.
 * @param fontStyle the typeface variant to use when drawing the letters (e.g., italic).
 * See [TextStyle.fontStyle].
 * @param fontWeight the typeface thickness to use when painting the text (e.g., [FontWeight.Bold]).
 * @param fontFamily the font family to be used when rendering the text. See [TextStyle.fontFamily].
 * @param letterSpacing the amount of space to add between each letter.
 * See [TextStyle.letterSpacing].
 * @param textDecoration the decorations to paint on the text (e.g., an underline).
 * See [TextStyle.textDecoration].
 * @param alignment The alignment of the text within its container.
 * @param overflow how visual overflow should be handled.
 * @param softWrap whether the text should break at soft line breaks. If false, the glyphs in the
 * text will be positioned as if there was unlimited horizontal space. If [softWrap] is false,
 * [overflow] and TextAlign may have unexpected effects.
 * @param maxLines An optional maximum number of lines for the text to span, wrapping if
 * necessary. If the text exceeds the given number of lines, it will be truncated according to
 * [overflow] and [softWrap]. It is required that 1 <= [minLines] <= [maxLines].
 * @param minLines The minimum height in terms of minimum number of visible lines. It is required
 * that 1 <= [minLines] <= [maxLines].
 * insert composables into text layout. See [InlineTextContent].
 * @param onTextLayout callback that is executed when a new text layout is calculated. A
 * [TextLayoutResult] object that callback provides contains paragraph information, size of the
 * text, baselines and other details. The callback can be used to add additional decoration or
 * functionality to the text. For example, to draw selection around the text.
 * @param style style configuration for the text such as color, font, line height etc.
 * @param lineSpacingRatio The ratio of line spacing to text size.
 *
 * @author Reda El Madini - For support, contact gladiatorkilo@gmail.com
 */
@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    suggestedFontSizes: ImmutableWrapper<List<TextUnit>> = emptyList<TextUnit>().toImmutableWrapper(),
    suggestedFontSizesStatus: SuggestedFontSizesStatus = suggestedFontSizes.rememberSuggestedFontSizesStatus,
    stepGranularityTextSize: TextUnit = TextUnit.Unspecified,
    minTextSize: TextUnit = TextUnit.Unspecified,
    maxTextSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    alignment: Alignment = Alignment.TopStart,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
    lineSpacingRatio: Float = style.lineHeight.value / style.fontSize.value,
) {
    AutoSizeText(
        text = AnnotatedString(text),
        modifier = modifier,
        color = color,
        suggestedFontSizes = suggestedFontSizes,
        suggestedFontSizesStatus = suggestedFontSizesStatus,
        stepGranularityTextSize = stepGranularityTextSize,
        minTextSize = minTextSize,
        maxTextSize = maxTextSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        alignment = alignment,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
        lineSpacingRatio = lineSpacingRatio,
    )
}

/**
 * Composable function that automatically adjusts the text size to fit within given constraints using AnnotatedString, considering the ratio of line spacing to text size.
 *
 * Features:
 *  Similar to AutoSizeText(String), with support for AnnotatedString.
 *
 * @param inlineContent a map storing composables that replaces certain ranges of the text, used to
 * insert composables into text layout. See [InlineTextContent].
 * @see AutoSizeText
 */
@Composable
fun AutoSizeText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    suggestedFontSizes: ImmutableWrapper<List<TextUnit>> = emptyList<TextUnit>().toImmutableWrapper(),
    suggestedFontSizesStatus: SuggestedFontSizesStatus = suggestedFontSizes.rememberSuggestedFontSizesStatus,
    stepGranularityTextSize: TextUnit = TextUnit.Unspecified,
    minTextSize: TextUnit = TextUnit.Unspecified,
    maxTextSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    alignment: Alignment = Alignment.TopStart,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: ImmutableWrapper<Map<String, InlineTextContent>> = mapOf<String, InlineTextContent>().toImmutableWrapper(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
    lineSpacingRatio: Float = style.lineHeight.value / style.fontSize.value,
) {
    // Change font scale to 1F
    CompositionLocalProvider(
        LocalDensity provides Density(density = LocalDensity.current.density, fontScale = 1F)
    ) {
        BoxWithConstraints(
            modifier = modifier,
            contentAlignment = alignment,
        ) {
            val combinedTextStyle = LocalTextStyle.current + style.copy(
                color = color.takeIf { it.isSpecified } ?: style.color,
                fontStyle = fontStyle ?: style.fontStyle,
                fontWeight = fontWeight ?: style.fontWeight,
                fontFamily = fontFamily ?: style.fontFamily,
                letterSpacing = letterSpacing.takeIf { it.isSpecified } ?: style.letterSpacing,
                textDecoration = textDecoration ?: style.textDecoration,
                textAlign = when (alignment) {
                    Alignment.TopStart, Alignment.CenterStart, Alignment.BottomStart -> TextAlign.Start
                    Alignment.TopCenter, Alignment.Center, Alignment.BottomCenter -> TextAlign.Center
                    Alignment.TopEnd, Alignment.CenterEnd, Alignment.BottomEnd -> TextAlign.End
                    else -> TextAlign.Unspecified
                },
            )

            val layoutDirection = LocalLayoutDirection.current
            val density = LocalDensity.current
            val fontFamilyResolver = LocalFontFamilyResolver.current
            val coercedLineSpacingRatio = lineSpacingRatio.takeIf { it.isFinite() && it >= 1 } ?: 1F
            val shouldMoveBackward: (TextUnit) -> Boolean = {
                shouldShrink(
                    text = text,
                    textStyle = combinedTextStyle.copy(
                        fontSize = it,
                        lineHeight = it * coercedLineSpacingRatio,
                    ),
                    minLines = minLines,
                    maxLines = maxLines,
                    softWrap = softWrap,
                    layoutDirection = layoutDirection,
                    density = density,
                    fontFamilyResolver = fontFamilyResolver,
                )
            }

            val electedFontSize = kotlin.run {
                if (suggestedFontSizesStatus == SuggestedFontSizesStatus.VALID)
                    suggestedFontSizes.value
                else
                    remember(key1 = suggestedFontSizes) {
                        suggestedFontSizes.value
                            .filter { it.isSp }
                            .takeIf { it.isNotEmpty() }
                            ?.sortedBy { it.value }
                    }
            }
                ?.findElectedValue(shouldMoveBackward = shouldMoveBackward)
                ?: rememberCandidateFontSizesIntProgress(
                    density = density,
                    dpSize = DpSize(maxWidth, maxHeight),
                    maxTextSize = maxTextSize,
                    minTextSize = minTextSize,
                    stepGranularityTextSize = stepGranularityTextSize,
                ).findElectedValue(
                    transform = { density.toSp(it) },
                    shouldMoveBackward = shouldMoveBackward,
                )

            Text(
                text = text,
                overflow = overflow,
                softWrap = softWrap,
                maxLines = maxLines,
                minLines = minLines,
                inlineContent = inlineContent.value,
                onTextLayout = onTextLayout,
                style = combinedTextStyle.copy(
                    fontSize = electedFontSize,
                    lineHeight = electedFontSize * coercedLineSpacingRatio,
                ),
            )
        }
    }
}

@OptIn(InternalFoundationTextApi::class)
private fun BoxWithConstraintsScope.shouldShrink(
    text: AnnotatedString,
    textStyle: TextStyle,
    minLines: Int,
    maxLines: Int,
    softWrap: Boolean,
    layoutDirection: LayoutDirection,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
) = TextDelegate(
    text = text,
    style = textStyle,
    maxLines = maxLines,
    minLines = minLines,
    softWrap = softWrap,
    overflow = TextOverflow.Clip,
    density = density,
    fontFamilyResolver = fontFamilyResolver,
).layout(
    constraints = constraints,
    layoutDirection = layoutDirection,
).hasVisualOverflow


private fun BoxWithConstraintsScope.shouldShrink2(
    text: AnnotatedString,
    textStyle: TextStyle,
    maxLines: Int,
    layoutDirection: LayoutDirection,
    softWrap: Boolean,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    textMeasurer: TextMeasurer,
) = textMeasurer.measure(
    text = text,
    style = textStyle,
    overflow = TextOverflow.Clip,
    softWrap = softWrap,
    maxLines = maxLines,
    constraints = constraints,
    layoutDirection = layoutDirection,
    density = density,
    fontFamilyResolver = fontFamilyResolver,
).hasVisualOverflow

@Composable
private fun rememberCandidateFontSizesIntProgress(
    density: Density,
    dpSize: DpSize,
    minTextSize: TextUnit = TextUnit.Unspecified,
    maxTextSize: TextUnit = TextUnit.Unspecified,
    stepGranularityTextSize: TextUnit = TextUnit.Unspecified,
): IntProgression {
    val max = remember(key1 = maxTextSize, key2 = dpSize, key3 = density) {
        val intSize = density.toIntSize(dpSize)
        min(intSize.width, intSize.height).let { max ->
            maxTextSize
                .takeIf { it.isSp }
                ?.let { density.roundToPx(it) }
                ?.coerceIn(range = 0..max)
                ?: max
        }
    }

    val min = remember(key1 = minTextSize, key2 = max, key3 = density) {
        minTextSize
            .takeIf { it.isSp }
            ?.let { density.roundToPx(it) }
            ?.coerceIn(range = 0..max)
            ?: 0
    }

    val step = remember(
        stepGranularityTextSize,
        min,
        max,
        density,
    ) {
        stepGranularityTextSize
            .takeIf { it.isSp }
            ?.let { density.roundToPx(it) }
            ?.coerceIn(minimumValue = 1, maximumValue = max - min)
            ?: 1
    }

    return remember(key1 = min, key2 = max, key3 = step) {
        min..max step step
    }
}

// This function works by using a binary search algorithm
fun <E> List<E>.findElectedValue(shouldMoveBackward: (E) -> Boolean) = run {
    indices.findElectedValue(
        transform = { this[it] },
        shouldMoveBackward = shouldMoveBackward,
    )
}

// This function works by using a binary search algorithm
private fun <E> IntProgression.findElectedValue(
    transform: (Int) -> E,
    shouldMoveBackward: (E) -> Boolean,
) = run {
    var low = first / step
    var high = last / step
    while (low <= high) {
        val mid = low + (high - low) / 2
        if (shouldMoveBackward(transform(mid * step)))
            high = mid - 1
        else
            low = mid + 1
    }
    transform((high * step).coerceAtLeast(minimumValue = first * step))
}

enum class SuggestedFontSizesStatus {
    VALID, INVALID, UNKNOWN;

    companion object {
        val List<TextUnit>.suggestedFontSizesStatus
            get() = if (isNotEmpty() && all { it.isSp } && sortedBy { it.value } == this)
                VALID
            else
                INVALID
        val ImmutableWrapper<List<TextUnit>>.rememberSuggestedFontSizesStatus
            @Composable get() = remember(key1 = this) { value.suggestedFontSizesStatus }
    }
}

@Immutable
data class ImmutableWrapper<T>(val value: T)

fun <T> T.toImmutableWrapper() = ImmutableWrapper(this)

operator fun <T> ImmutableWrapper<T>.getValue(thisRef: Any?, property: KProperty<*>) = value

private fun Density.roundToPx(sp: TextUnit): Int = sp.roundToPx()

private fun Density.toSp(px: Int): TextUnit = px.toSp()

private fun Density.toIntSize(dpSize: DpSize): IntSize =
    IntSize(dpSize.width.roundToPx(), dpSize.height.roundToPx())
