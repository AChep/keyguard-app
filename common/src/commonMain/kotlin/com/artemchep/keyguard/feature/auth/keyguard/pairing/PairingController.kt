package com.artemchep.keyguard.feature.auth.keyguard.pairing

import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.provider.keyguard.enroll.EnrollmentStep
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_pairing_sas_enter_error_wrong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** The pairing of a screen: in which [mode] it started, and its [step]. */
internal data class PairingState(
    val mode: PairingMode,
    val step: PairingStep,
)

/**
 * The pairings of a [screen], one at a time. A code works once, so every
 * attempt is a new pairing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PairingController(
    private val screen: RememberStateFlowScope,
) {
    /** A pairing, and what starts it again. */
    private class Attempt(
        val run: PairingRun,
        val create: (CoroutineScope) -> KeyguardPairing,
    )

    private val copyText = screen.copier()

    private val codeInput = PairingCodeInput()

    private val sasInput = PairingSasInput()

    private val attemptSink = MutableStateFlow<Attempt?>(null)

    /** Emits once a pairing succeeded. */
    val doneFlow: Flow<EnrollmentStep.Done> = attemptSink
        .flatMapLatest { attempt ->
            attempt?.run?.pairing?.step
                ?: emptyFlow()
        }
        .filterIsInstance<EnrollmentStep.Done>()

    /** The pairing, or `null` while there is none. */
    val stateFlow: Flow<PairingState?> = attemptSink
        .flatMapLatest { attempt ->
            attempt
                ?: return@flatMapLatest flowOf(null)
            val run = attempt.run
            combine(
                run.progressFlow,
                codeInput.modelFlow(screen),
                sasInput.changes,
            ) { progress, codeModel, _ ->
                // Says what went wrong, as the other screens do.
                val causeMessage = progress.cause
                    ?.let { getErrorReadableMessage(it, screen).title }
                val wrongSasText = if (progress.step is EnrollmentStep.Verify) {
                    screen.translate(Res.string.app_link_pairing_sas_enter_error_wrong)
                } else {
                    ""
                }
                val step = progress.toPairingStep(
                    run = run,
                    scan = {
                        codeInput.scanStep(
                            model = codeModel,
                            run = run,
                        )
                    },
                    enterSas = { verify ->
                        sasInput.step(
                            verify = verify,
                            run = run,
                            wrongText = wrongSasText,
                        )
                    },
                    causeMessage = causeMessage,
                    onCopy = { code ->
                        copyText.copy(code, hidden = true)
                    },
                    onRestart = {
                        start(run.mode, attempt.create)
                    },
                )
                PairingState(
                    mode = run.mode,
                    step = step,
                )
            }
        }

    /** Stops the pairing, if any, and starts a new one in the [mode] with [create]. */
    fun start(
        mode: PairingMode,
        create: (CoroutineScope) -> KeyguardPairing,
    ) {
        attemptSink.value?.run?.cancel()
        codeInput.clear()
        sasInput.clear()
        val run = screen.launchPairing(mode, create)
        attemptSink.value = Attempt(
            run = run,
            create = create,
        )
    }

    /** Stops the pairing; there is none until the next [start]. */
    fun cancel() {
        attemptSink.value?.run?.cancel()
        attemptSink.value = null
    }
}

/**
 * `true` while the user may drop the pairing: until the devices check
 * the digits, or once it failed. Rejecting the digits, or cancelling, is
 * the way out of the check.
 */
internal val PairingStep.canDrop: Boolean
    get() = when (this) {
        PairingStep.Starting,
        is PairingStep.ShowCode,
        is PairingStep.Scan,
        PairingStep.Connecting,
        is PairingStep.Failed,
        -> true

        is PairingStep.EnterSas,
        is PairingStep.Verify,
        PairingStep.Transferring,
        is PairingStep.Done,
        -> false
    }
