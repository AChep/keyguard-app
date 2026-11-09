package com.artemchep.keyguard.feature.auth.keyguard.pairing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_pairing_sas_compare_text
import com.artemchep.keyguard.res.app_link_pairing_sas_enter_label
import com.artemchep.keyguard.res.app_link_pairing_sas_enter_title
import com.artemchep.keyguard.res.app_link_pairing_sas_match_yes
import com.artemchep.keyguard.res.app_link_pairing_sas_matched_text
import com.artemchep.keyguard.res.app_link_pairing_sas_matched_title
import com.artemchep.keyguard.res.app_link_pairing_sas_title
import com.artemchep.keyguard.res.app_link_pairing_sas_waiting_text
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.continue_
import com.artemchep.keyguard.res.no
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.okContainer
import com.artemchep.keyguard.ui.theme.onOkContainer
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Shows the digits both devices must agree on. Until its user confirmed, this device
 * scanned the code: the user enters the digits on the other device, then confirms that
 * both show the same, or rejects. Once confirmed, it shows the digits as matching and
 * waits for the other device.
 */
@Composable
fun SasPanel(
    step: PairingStep.Verify,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (step.confirmed) {
            SasMatched()
        } else {
            Text(
                text = stringResource(Res.string.app_link_pairing_sas_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        PairingSasDigits(
            sas = step.sas,
            matched = step.confirmed,
        )
        Spacer(Modifier.height(12.dp))
        SasPeerLabel(
            peerLabel = step.peerLabel,
        )
        Spacer(Modifier.height(16.dp))
        val text = when {
            !step.confirmed -> Res.string.app_link_pairing_sas_compare_text
            // The user of the other device still confirms there.
            step.entered -> Res.string.app_link_pairing_sas_matched_text
            else -> Res.string.app_link_pairing_sas_waiting_text
        }
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(24.dp))
    SasButtons(
        rejectText = if (step.confirmed) Res.string.cancel else Res.string.no,
        onReject = step.onReject,
        approveText = Res.string.app_link_pairing_sas_match_yes,
        onApprove = step.onApprove,
    )
}

/**
 * Asks to type the digits the other device shows: this device showed the code. A wrong
 * entry shows an error; the user types again, or cancels.
 */
@Composable
fun SasEntryPanel(
    step: PairingStep.EnterSas,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.app_link_pairing_sas_enter_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        SasPeerLabel(
            peerLabel = step.peerLabel,
        )
    }
    Spacer(Modifier.height(16.dp))
    FlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.fieldHorizontalPadding),
        label = stringResource(Res.string.app_link_pairing_sas_enter_label),
        value = step.digits,
        textStyle = MaterialTheme.typography.headlineSmall.copy(
            fontFamily = monoFontFamily,
        ),
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                step.onSubmit?.invoke()
            },
        ),
        leading = {
            IconBox(
                main = Icons.Outlined.Pin,
            )
        },
    )
    Spacer(Modifier.height(24.dp))
    SasButtons(
        rejectText = Res.string.cancel,
        onReject = step.onCancel,
        approveText = Res.string.continue_,
        onApprove = step.onSubmit,
        approveVisible = true,
    )
}

/** Says that the digits match, with a check mark. */
@Composable
private fun SasMatched() {
    val color = MaterialTheme.colorScheme.ok
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = color,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.app_link_pairing_sas_matched_title),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SasPeerLabel(
    peerLabel: String,
) {
    Text(
        text = truncatePairingPeerLabel(peerLabel),
        style = MaterialTheme.typography.titleSmall,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The way out, [onReject], and the way on, [onApprove]. The way on shows while it is
 * set, or always if [approveVisible], disabled while it is not set.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SasButtons(
    rejectText: StringResource,
    onReject: () -> Unit,
    approveText: StringResource,
    onApprove: (() -> Unit)?,
    approveVisible: Boolean = onApprove != null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val buttonModifier = Modifier
            .heightIn(min = ButtonDefaults.MediumContainerHeight)
        OutlinedButton(
            modifier = buttonModifier
                .weight(1f, fill = approveVisible),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.MediumContentPadding,
            onClick = onReject,
        ) {
            Text(
                text = stringResource(rejectText),
                textAlign = TextAlign.Center,
            )
        }
        if (approveVisible) {
            Button(
                modifier = buttonModifier
                    .weight(APPROVE_WEIGHT),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.MediumContentPadding,
                enabled = onApprove != null,
                onClick = {
                    onApprove?.invoke()
                },
            ) {
                Text(
                    text = stringResource(approveText),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private const val APPROVE_WEIGHT = 1.4f

private val SasTileWidth = 44.dp

private val SasTileHeight = 60.dp

private val SasTileShape = RoundedCornerShape(16.dp)

private const val SAS_GROUP = 3

private const val SAS_ROLL_TICKS = 6

private const val SAS_ROLL_TICK_MILLIS = 45L

private const val SAS_STAGGER_MILLIS = 70L

/**
 * The digits of a pairing as two groups of tiles. Each tile rolls through a few digits
 * before it lands on its own, one after another, with a tick.
 */
@Composable
fun PairingSasDigits(
    modifier: Modifier = Modifier,
    sas: String,
    /** `true` to show the digits as matching the ones of the other device. */
    matched: Boolean = false,
) {
    // Read out one digit at a time.
    val description = sas.toList().joinToString(separator = " ")
    Row(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = description
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sas.forEachIndexed { index, digit ->
            if (index > 0) {
                val gap = if (index % SAS_GROUP == 0) 16.dp else 6.dp
                Spacer(Modifier.width(gap))
            }
            PairingSasTile(
                digit = digit,
                index = index,
                matched = matched,
            )
        }
    }
}

/** The digit a tile shows while it rolls, and how big it is as it lands. */
private class SasTileRoll(
    val shown: State<Int>,
    val scale: State<Float>,
)

/**
 * Rolls the tile [index] through a few digits before it lands on its [digit], a little
 * after the tiles before it, with a tick.
 */
@Composable
private fun rememberSasTileRoll(
    digit: Char,
    index: Int,
): SasTileRoll {
    val motionEnabled = rememberMotionEnabled()
    val haptic = LocalHapticFeedback.current
    val final = digit.digitToIntOrNull() ?: 0
    val shown = remember(digit) {
        mutableIntStateOf(if (motionEnabled) (final + index + 1) % DIGITS else final)
    }
    val bounce = remember(digit) { Animatable(if (motionEnabled) INITIAL_SCALE else 1f) }
    LaunchedEffect(digit, motionEnabled) {
        if (!motionEnabled) {
            shown.intValue = final
            bounce.snapTo(1f)
            return@LaunchedEffect
        }
        delay(SAS_STAGGER_MILLIS * index)
        repeat(SAS_ROLL_TICKS) { tick ->
            shown.intValue = (final + tick * ROLL_STEP + index) % DIGITS
            delay(SAS_ROLL_TICK_MILLIS)
        }
        shown.intValue = final
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        bounce.snapTo(LANDING_SCALE)
        bounce.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }
    return SasTileRoll(
        shown = shown,
        scale = bounce.asState(),
    )
}

@Composable
private fun PairingSasTile(
    digit: Char,
    index: Int,
    matched: Boolean,
) {
    val roll = rememberSasTileRoll(digit, index)
    val containerColor = if (matched) {
        MaterialTheme.colorScheme.okContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (matched) {
        MaterialTheme.colorScheme.onOkContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Box(
        modifier = Modifier
            .size(width = SasTileWidth, height = SasTileHeight)
            .graphicsLayer {
                val scale = roll.scale.value
                scaleX = scale
                scaleY = scale
            }
            .background(
                color = containerColor,
                shape = SasTileShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = roll.shown.value,
            transitionSpec = {
                val enter = slideInVertically { it / 2 } + fadeIn()
                val exit = slideOutVertically { -it / 2 } + fadeOut()
                enter togetherWith exit
            },
            label = "sas_digit",
        ) { value ->
            Text(
                text = value.toString(),
                fontFamily = monoFontFamily,
                style = MaterialTheme.typography.headlineLarge,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

private const val DIGITS = 10

private const val ROLL_STEP = 3

private const val LANDING_SCALE = 1.18f

private const val INITIAL_SCALE = 0.6f
