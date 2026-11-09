package com.artemchep.keyguard.feature.auth.keyguard.pairing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentRole
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_pairing_connecting_text
import com.artemchep.keyguard.res.app_link_pairing_done_title
import com.artemchep.keyguard.res.app_link_pairing_starting_text
import com.artemchep.keyguard.res.app_link_pairing_transferring_text
import com.artemchep.keyguard.res.retry
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionallyKeepScreenOnEffect
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Shows a pairing: the [PairingStage] and, under it, the panel of the [step]. Without a
 * step it shows the [idle] content, such as the ways to pair.
 *
 * @param sensitiveCode `true` to warn that a shown code admits devices to the sync group.
 * @param doneActions what the user does once the devices are linked.
 */
@Composable
fun PairingStepContent(
    step: PairingStep?,
    sensitiveCode: Boolean = false,
    idle: @Composable ColumnScope.() -> Unit = {},
    doneActions: @Composable () -> Unit = {},
) {
    val phase = step.toStagePhase()
    PairingFeedbackEffect(phase)
    if (step != null && phase != PairingStagePhase.DONE && phase != PairingStagePhase.FAILED) {
        // The other device may take a moment to scan the code.
        OptionallyKeepScreenOnEffect()
    }

    // The data travels from the device of the link to the one that
    // joins it; only the check of the digits tells which this is.
    var role by remember { mutableStateOf(EnrollmentRole.MEMBER) }
    LaunchedEffect(step) {
        when (step) {
            is PairingStep.Verify -> role = step.role
            is PairingStep.EnterSas -> role = step.role
            else -> {
                // The role stays.
            }
        }
    }

    PairingStage(
        phase = phase,
        sending = role == EnrollmentRole.MEMBER,
    )
    Spacer(Modifier.height(8.dp))

    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        modifier = Modifier
            .fillMaxWidth(),
        targetState = step,
        contentKey = { s -> s?.let { it::class } },
        transitionSpec = {
            val enter = fadeIn(motion.defaultEffectsSpec()) +
                slideInVertically(motion.defaultSpatialSpec()) { it / PANEL_SLIDE_FRACTION }
            val exit = fadeOut(motion.fastEffectsSpec())
            (enter togetherWith exit)
                .using(SizeTransform(clip = false))
        },
        label = "pairing_step",
    ) { s ->
        Column(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            PairingStepPanel(
                step = s,
                sensitiveCode = sensitiveCode,
                idle = idle,
                doneActions = doneActions,
            )
        }
    }
}

private const val PANEL_SLIDE_FRACTION = 6

@Composable
private fun ColumnScope.PairingStepPanel(
    step: PairingStep?,
    sensitiveCode: Boolean,
    idle: @Composable ColumnScope.() -> Unit,
    doneActions: @Composable () -> Unit,
) {
    when (step) {
        null -> idle()
        PairingStep.Starting -> PairingWaiting(Res.string.app_link_pairing_starting_text)
        is PairingStep.ShowCode -> PairingCodePanel(
            step = step,
            sensitive = sensitiveCode,
        )

        is PairingStep.Scan -> PairingScanPanel(
            step = step,
        )

        PairingStep.Connecting -> PairingWaiting(Res.string.app_link_pairing_connecting_text)
        is PairingStep.EnterSas -> SasEntryPanel(
            step = step,
        )

        is PairingStep.Verify -> SasPanel(
            step = step,
        )

        PairingStep.Transferring -> PairingWaiting(Res.string.app_link_pairing_transferring_text)
        is PairingStep.Done -> PairingDone(
            step = step,
            actions = doneActions,
        )

        is PairingStep.Failed -> PairingFailed(
            step = step,
        )
    }
}

/** A confirming buzz once the devices are linked, a rejecting one if the pairing failed. */
@Composable
private fun PairingFeedbackEffect(
    phase: PairingStagePhase,
) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(phase) {
        when (phase) {
            PairingStagePhase.DONE -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            PairingStagePhase.FAILED -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
            else -> {
                // Nothing to feel.
            }
        }
    }
}

@Composable
private fun PairingWaiting(
    text: StringResource,
) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 16.dp,
            ),
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
}

@Composable
private fun PairingDone(
    step: PairingStep.Done,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.app_link_pairing_done_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        val peerLabel = step.peerLabel
        if (peerLabel != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = truncatePairingPeerLabel(peerLabel),
                style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    actions()
}

@Composable
private fun PairingFailed(
    step: PairingStep.Failed,
) {
    PairingErrorPanel(
        error = step.error,
        message = step.message,
        actionText = stringResource(Res.string.retry),
        onAction = step.onRetry,
    )
}
