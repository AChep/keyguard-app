@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.artemchep.keyguard.feature.auth.keyguard.pairing

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.feature.barcodetype.BarcodeImage
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_pairing_code_sensitive_note
import com.artemchep.keyguard.res.app_link_pairing_copy_code
import com.artemchep.keyguard.res.app_link_pairing_expires_in
import com.artemchep.keyguard.res.app_link_pairing_new_code
import com.artemchep.keyguard.res.app_link_pairing_show_code_text
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.theme.warning
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val QR_MAX_SIZE = 260.dp

private val QR_RING_INSET = 10.dp

private val QR_RING_WIDTH = 4.dp

private val QR_RING_RADIUS = 28.dp

private const val CODE_MAX_LINES = 3

private const val QUARTER = 90f

/**
 * Shows the code of the [step] as a QR code and as text, with the time left.
 * Once the code is gone, shows why and a button for a new one.
 */
@Composable
fun PairingCodePanel(
    step: PairingStep.ShowCode,
    /** `true` to warn that the code admits devices to the sync group. */
    sensitive: Boolean = false,
) {
    if (step.expired) {
        PairingCodeExpired(
            step = step,
        )
        return
    }

    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = stringResource(Res.string.app_link_pairing_show_code_text),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    val remaining = rememberPairingCodeRemaining(
        expiresAt = step.expiresAt,
    )
    PairingCodeQr(
        code = step.code,
        remaining = remaining,
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PairingCodeCountdown(
            remaining = remaining.left,
        )
    }
    Spacer(Modifier.height(16.dp))
    PairingCodeText(
        code = step.code,
        onCopy = step.onCopy,
    )
    if (sensitive) {
        Spacer(Modifier.height(8.dp))
        FlatSimpleNote(
            type = SimpleNote.Type.WARNING,
            text = stringResource(Res.string.app_link_pairing_code_sensitive_note),
        )
    }
}

@Composable
private fun PairingCodeText(
    code: String,
    onCopy: () -> Unit,
) {
    SelectionContainer {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = code,
            fontFamily = monoFontFamily,
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            maxLines = CODE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        horizontalArrangement = Arrangement.Center,
    ) {
        FilledTonalButton(
            shapes = ButtonDefaults.shapes(),
            onClick = onCopy,
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = null,
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(
                text = stringResource(Res.string.app_link_pairing_copy_code),
            )
        }
    }
}

@Composable
private fun PairingCodeExpired(
    step: PairingStep.ShowCode,
) {
    val error = step.error
        ?: return
    PairingErrorPanel(
        error = error,
        message = step.message,
        actionText = stringResource(Res.string.app_link_pairing_new_code),
        onAction = step.onNewCode,
    )
}

/** How long a code has left, and what part of its life that is. */
private class PairingCodeRemaining(
    val left: Duration,
    /** From 1 when the code was first shown, to 0 once it expires. */
    val fraction: Float,
)

@Composable
private fun rememberPairingCodeRemaining(
    expiresAt: Instant,
): PairingCodeRemaining {
    // The code does not say when it was made: its
    // life starts once this screen first shows it.
    val total = remember(expiresAt) {
        remainingUntil(expiresAt).coerceAtLeast(1.seconds)
    }
    val left by produceState(
        initialValue = remainingUntil(expiresAt),
        expiresAt,
    ) {
        while (value > Duration.ZERO) {
            delay(1.seconds)
            value = remainingUntil(expiresAt)
        }
    }
    return PairingCodeRemaining(
        left = left,
        fraction = (left / total).toFloat().coerceIn(0f, 1f),
    )
}

@Composable
private fun PairingCodeQr(
    code: String,
    remaining: PairingCodeRemaining,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        val ringInset = QR_RING_INSET
        val imageSize = minOf(maxWidth - ringInset * 2, QR_MAX_SIZE)
        val imageSizePx = with(LocalDensity.current) { imageSize.roundToPx() }
        // Keep the same lambda, so the image is not
        // generated again on every recomposition.
        val imageModel: () -> BarcodeImageRequest? = remember(code, imageSizePx) {
            val request = BarcodeImageRequest(
                format = BarcodeImageFormat.QR_CODE,
                size = BarcodeImageRequest.Size(
                    width = imageSizePx,
                    height = imageSizePx,
                ),
                data = code,
            )
            val model: () -> BarcodeImageRequest? = { request }
            model
        }
        // The ring runs out with the code, and warns once it is almost gone.
        val fraction by animateFloatAsState(
            targetValue = remaining.fraction,
            animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
            label = "pairing_code_ring",
        )
        val ringColor by animateColorAsState(
            targetValue = when {
                remaining.left < 10.seconds -> MaterialTheme.colorScheme.error
                remaining.left < 30.seconds -> MaterialTheme.colorScheme.warning
                else -> MaterialTheme.colorScheme.primary
            },
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "pairing_code_ring_color",
        )
        val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        Box(
            modifier = Modifier
                .size(imageSize + ringInset * 2)
                .countdownRing(
                    fraction = { fraction },
                    color = { ringColor },
                    trackColor = trackColor,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BarcodeImage(
                modifier = Modifier
                    .size(imageSize)
                    // The code must stay readable in the dark theme.
                    .background(Color.White, MaterialTheme.shapes.large)
                    .padding(10.dp),
                imageModel = imageModel,
            )
        }
    }
}

/** A ring around the bounds that runs out from twelve o'clock as the [fraction] drops. */
private fun Modifier.countdownRing(
    fraction: () -> Float,
    color: () -> Color,
    trackColor: Color,
): Modifier = drawWithCache {
    val stroke = QR_RING_WIDTH.toPx()
    val ring = Path()
    roundRectFromTop(
        path = ring,
        inset = stroke / 2f,
        radius = QR_RING_RADIUS.toPx(),
    )
    val measure = PathMeasure()
    measure.setPath(ring, false)
    val segment = Path()
    onDrawBehind {
        drawPath(
            path = ring,
            color = trackColor,
            style = Stroke(width = stroke),
        )
        segment.rewind()
        measure.getSegment(
            startDistance = 0f,
            stopDistance = measure.length * fraction(),
            destination = segment,
            startWithMoveTo = true,
        )
        drawPath(
            path = segment,
            color = color(),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * Fills the [path] with a rounded rectangle [inset] in the bounds, that starts at the
 * middle of the top edge and runs clockwise: the countdown runs out from twelve o'clock.
 */
private fun CacheDrawScope.roundRectFromTop(
    path: Path,
    inset: Float,
    radius: Float,
) {
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    val r = radius.coerceAtMost(minOf(right - left, bottom - top) / 2f)
    path.rewind()
    path.moveTo((left + right) / 2f, top)
    path.lineTo(right - r, top)
    path.arcTo(
        rect = Rect(right - 2 * r, top, right, top + 2 * r),
        startAngleDegrees = -QUARTER,
        sweepAngleDegrees = QUARTER,
        forceMoveTo = false,
    )
    path.lineTo(right, bottom - r)
    path.arcTo(
        rect = Rect(right - 2 * r, bottom - 2 * r, right, bottom),
        startAngleDegrees = 0f,
        sweepAngleDegrees = QUARTER,
        forceMoveTo = false,
    )
    path.lineTo(left + r, bottom)
    path.arcTo(
        rect = Rect(left, bottom - 2 * r, left + 2 * r, bottom),
        startAngleDegrees = QUARTER,
        sweepAngleDegrees = QUARTER,
        forceMoveTo = false,
    )
    path.lineTo(left, top + r)
    path.arcTo(
        rect = Rect(left, top, left + 2 * r, top + 2 * r),
        startAngleDegrees = 2 * QUARTER,
        sweepAngleDegrees = QUARTER,
        forceMoveTo = false,
    )
    path.close()
}

@Composable
private fun PairingCodeCountdown(
    remaining: Duration,
) {
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                CircleShape,
            )
            .padding(
                horizontal = 12.dp,
                vertical = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier
                .size(16.dp),
            imageVector = Icons.Outlined.Timer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(
                Res.string.app_link_pairing_expires_in,
                formatPairingCountdown(remaining),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center,
        )
    }
}

private fun remainingUntil(expiresAt: Instant): Duration =
    (expiresAt - Clock.System.now()).coerceAtLeast(Duration.ZERO)

/** Returns the [remaining] time as `m:ss`. */
internal fun formatPairingCountdown(remaining: Duration): String {
    val totalSeconds = remaining.inWholeSeconds.coerceAtLeast(0L)
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:" + seconds.toString().padStart(2, '0')
}

private const val SECONDS_PER_MINUTE = 60L
