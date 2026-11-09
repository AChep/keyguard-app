package com.artemchep.keyguard.feature.auth.keyguard.pairing

import com.artemchep.keyguard.feature.auth.common.TextCell
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.modelFlow
import com.artemchep.keyguard.feature.auth.common.toModel
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.p2psync.enroll.DisplayMode
import com.artemchep.keyguard.feature.p2psync.model.PairingCode
import com.artemchep.keyguard.feature.p2psync.model.PairingCodeDecoding
import com.artemchep.keyguard.feature.p2psync.model.PairingCodeError
import com.artemchep.keyguard.provider.keyguard.enroll.EnrollmentStep
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardPairingError
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_pairing_error_invalid_code
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job

/**
 * One pairing a screen started, in its [mode]. It lives until it is cancelled
 * or the screen closes; closing the screen cancels the [pairing] too.
 */
internal class PairingRun(
    val pairing: KeyguardPairing,
    val mode: PairingMode,
    screenScope: CoroutineScope,
) {
    private val mismatchSink = MutableStateFlow(false)

    private val screenJob = screenScope.coroutineContext.job

    private val job = SupervisorJob(screenJob)

    private val screenCompletion = screenJob.invokeOnCompletion {
        pairing.cancel()
    }

    /**
     * What the pairing went through. Collected for as long as the run lives,
     * so a screen that stops collecting does not forget the earlier steps.
     */
    val progressFlow: StateFlow<PairingProgress> = combine(
        pairing.step.runningFold(PairingProgress()) { progress, step -> progress.next(step) },
        mismatchSink,
    ) { progress, mismatch ->
        progress.copy(mismatch = mismatch)
    }.stateIn(
        scope = CoroutineScope(screenScope.coroutineContext + job),
        started = SharingStarted.Eagerly,
        initialValue = PairingProgress(),
    )

    /** The user saw different digits on the two devices. */
    fun rejectAsMismatch() {
        mismatchSink.value = true
        pairing.reject()
    }

    /** Stops the pairing; the screen starts another one, or closes. */
    fun cancel() {
        job.cancel()
        screenCompletion.dispose()
        pairing.cancel()
    }
}

/**
 * What a pairing went through: the current [step], and what the earlier
 * steps showed.
 */
internal data class PairingProgress(
    val step: EnrollmentStep = EnrollmentStep.Method,
    /** The last code this device showed. */
    val lastCode: EnrollmentStep.ShowCode? = null,
    /** The name of the other device, once the devices met. */
    val peerLabel: String? = null,
    /** `true` once the user said the digits differ. */
    val mismatch: Boolean = false,
) {
    fun next(step: EnrollmentStep): PairingProgress = copy(
        step = step,
        lastCode = step as? EnrollmentStep.ShowCode ?: lastCode,
        peerLabel = (step as? EnrollmentStep.Verify)?.peerLabel ?: peerLabel,
    )

    /** What made the pairing fail as [KeyguardPairingError.FAILED], if known. */
    val cause: Throwable?
        get() = (step as? EnrollmentStep.Failed)
            ?.takeIf { it.reason == KeyguardPairingError.FAILED }
            ?.cause

    /** Why the pairing failed, or `null` if it did not. */
    val error: KeyguardPairingError?
        get() {
            val reason = (step as? EnrollmentStep.Failed)?.reason
                ?: return null
            // Rejecting the digits ends the pairing as
            // cancelled by this device.
            return if (reason == KeyguardPairingError.CANCELLED && mismatch) {
                KeyguardPairingError.MISMATCH
            } else {
                reason
            }
        }
}

/**
 * Starts a pairing with [create] and picks the [mode]. The pairing runs in the app
 * scope, so that its last frames, such as the acknowledgement of the group, reach the
 * other device even if the screen closes right after; closing the screen cancels it.
 */
internal fun RememberStateFlowScope.launchPairing(
    mode: PairingMode,
    create: (CoroutineScope) -> KeyguardPairing,
): PairingRun {
    val pairing = create(appScope)
    when (mode) {
        PairingMode.SHOW_CODE -> pairing.showCode()
        PairingMode.SCAN_CODE -> pairing.scanCode()
    }
    return PairingRun(
        pairing = pairing,
        mode = mode,
        screenScope = screenScope,
    )
}

/**
 * Returns the step the screens show for this progress of the [run].
 *
 * @param scan builds the scan step, with the code field of the screen.
 * @param enterSas builds the step that types the digits of the other device, with the
 *   digits field of the screen.
 * @param causeMessage says what went wrong, from the [PairingProgress.cause].
 * @param onCopy copies a code.
 * @param onRestart starts a new pairing in the same mode.
 */
@Suppress("LongParameterList") // The step and the actions of the screen.
internal fun PairingProgress.toPairingStep(
    run: PairingRun,
    scan: () -> PairingStep.Scan,
    enterSas: (EnrollmentStep.Verify) -> PairingStep.EnterSas,
    causeMessage: String? = null,
    onCopy: (String) -> Unit,
    onRestart: () -> Unit,
): PairingStep = when (val s = step) {
    EnrollmentStep.Method -> PairingStep.Starting
    is EnrollmentStep.ShowCode -> PairingStep.ShowCode(
        code = s.code,
        expiresAt = s.expiresAt,
        onCopy = {
            onCopy(s.code)
        },
    )

    EnrollmentStep.Scanning -> scan()
    EnrollmentStep.Connecting -> PairingStep.Connecting
    // The device that showed the code types the digits of the other one,
    // and shows them only once they match.
    is EnrollmentStep.Verify -> if (s.display == DisplayMode.DISPLAYER && !s.confirmed) {
        enterSas(s)
    } else {
        s.toVerifyStep(run)
    }

    EnrollmentStep.Transferring -> PairingStep.Transferring
    is EnrollmentStep.Done -> PairingStep.Done(
        peerLabel = peerLabel,
    )

    is EnrollmentStep.Failed -> {
        val error = error ?: s.reason
        // A code works once: show that it is gone and offer a new one.
        val code = lastCode
            ?.takeIf { run.mode == PairingMode.SHOW_CODE }
        if (code != null) {
            PairingStep.ShowCode(
                code = code.code,
                expiresAt = code.expiresAt,
                error = error,
                message = causeMessage,
                onNewCode = onRestart,
                onCopy = {
                    // The code no longer works.
                },
            )
        } else {
            PairingStep.Failed(
                error = error,
                message = causeMessage,
                onRetry = onRestart,
            )
        }
    }
}

/**
 * `true` if [text] can be handed to a pairing: a pairing code, or a code of
 * another version, which the pairing reports as [KeyguardPairingError.UPDATE_REQUIRED]
 * or [KeyguardPairingError.PEER_UPDATE_REQUIRED].
 */
internal fun isAcceptedPairingCode(text: String): Boolean = when (val decoded = PairingCode.decode(text)) {
    is PairingCodeDecoding.Valid -> true
    is PairingCodeDecoding.Invalid -> when (decoded.error) {
        PairingCodeError.NEWER_VERSION,
        PairingCodeError.OLDER_VERSION,
        -> true

        PairingCodeError.WRONG_PREFIX,
        PairingCodeError.MALFORMED,
        PairingCodeError.WRONG_SCOPE,
        -> false
    }
}

/** Returns the step that shows the digits of this step of the [run], see [PairingStep.Verify]. */
private fun EnrollmentStep.Verify.toVerifyStep(run: PairingRun): PairingStep.Verify = PairingStep.Verify(
    sas = sas,
    peerLabel = peerLabel,
    role = role,
    // The user of each device confirms; the member also admits the joiner.
    onApprove = if (confirmed) {
        null
    } else {
        // lambda
        {
            run.pairing.approve()
        }
    },
    onReject = if (confirmed) run.pairing::cancel else run::rejectAsMismatch,
    confirmed = confirmed,
    entered = display == DisplayMode.DISPLAYER,
)

/**
 * The field the device that showed the code types the digits of the other device into.
 * Six digits are checked at once, as is a submit. Never persisted: the digits work for one
 * pairing only.
 */
internal class PairingSasInput {
    /** The [digits] handed over last, and how many typed digits had missed by then. */
    private class Entry(
        val digits: String,
        val missesBefore: Int,
    )

    private val handle = TextFieldHandle(
        sink = MutableStateFlow(TextCell(text = "")),
        id = "pairing_sas",
    )

    private val entrySink = MutableStateFlow<Entry?>(null)

    /** Emits whenever the field or the last entry changes. */
    val changes: Flow<Unit> = combine(handle.sink, entrySink) { _, _ -> }

    fun clear() {
        handle.setText("")
        entrySink.value = null
    }

    /**
     * Returns the step of the [run] that types the digits of the [verify] step; the field
     * shows the [wrongText] while it holds the digits that missed last.
     */
    fun step(
        verify: EnrollmentStep.Verify,
        run: PairingRun,
        wrongText: String,
    ): PairingStep.EnterSas {
        val digits = sasDigitsOf(handle.sink.value.text)
        val entry = entrySink.value
        // The pairing counted the entry as a miss, and the user did not change it since.
        val wrong = entry != null &&
            verify.misses > entry.missesBefore &&
            entry.digits == digits

        fun submit(value: String) {
            entrySink.value = Entry(value, verify.misses)
            run.pairing.enterDigits(value)
        }

        val model = handle
            .toModel(error = wrongText.takeIf { wrong })
            .copy(
                onChange = { text ->
                    handle.onChange(text)
                    sasDigitsOf(text)
                        ?.takeIf { it != digits }
                        ?.let(::submit)
                },
            )
        return PairingStep.EnterSas(
            peerLabel = verify.peerLabel,
            role = verify.role,
            digits = model,
            onSubmit = digits?.let { value ->
                // lambda
                {
                    submit(value)
                }
            },
            onCancel = run.pairing::cancel,
        )
    }
}

/** Returns the six digits [text] holds, without spaces, or `null` if it holds anything else. */
internal fun sasDigitsOf(text: String): String? = text
    .filterNot(Char::isWhitespace)
    .takeIf { digits -> digits.length == SAS_LENGTH && digits.all { it in '0'..'9' } }

/** The field a pairing code is pasted into. The code is a secret, so it is never persisted. */
internal class PairingCodeInput {
    private val handle = TextFieldHandle(
        sink = MutableStateFlow(TextCell(text = "")),
        id = "pairing_code",
    )

    fun modelFlow(translator: TranslatorScope): Flow<TextFieldModel> = handle
        .modelFlow { text ->
            val code = text.trim()
            if (code.isEmpty() || isAcceptedPairingCode(code)) {
                Validated.Success(code)
            } else {
                Validated.Failure(
                    model = code,
                    error = translator.translate(Res.string.app_link_pairing_error_invalid_code),
                )
            }
        }

    fun clear() = handle.setText("")

    /** Returns the scan step of the [run], with the field [model]. */
    fun scanStep(
        model: TextFieldModel,
        run: PairingRun,
    ): PairingStep.Scan {
        val text = model.text.trim()
        val onSubmit = if (text.isNotEmpty() && isAcceptedPairingCode(text)) {
            // lambda
            {
                run.pairing.submitCode(text)
            }
        } else {
            null
        }
        return PairingStep.Scan(
            code = model,
            onCode = { raw ->
                val code = raw.trim()
                // Show what was scanned, with an error
                // if it is not a pairing code.
                handle.setText(code)
                if (isAcceptedPairingCode(code)) {
                    run.pairing.submitCode(code)
                }
            },
            onSubmit = onSubmit,
        )
    }
}
