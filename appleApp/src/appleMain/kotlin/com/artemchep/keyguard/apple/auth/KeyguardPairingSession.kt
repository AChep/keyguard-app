package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.apple.model.toFieldSnapshot
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.applink.appLinkPairStateProducer
import com.artemchep.keyguard.feature.auth.keyguard.KeyguardPairState
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingMode
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingStagePhase
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingStep
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentRole
import com.artemchep.keyguard.feature.auth.keyguard.pairing.formatPairingSas
import com.artemchep.keyguard.feature.auth.keyguard.pairing.toStagePhase
import com.artemchep.keyguard.feature.auth.keyguard.pairing.textRes
import com.artemchep.keyguard.feature.auth.keyguard.pairing.truncatePairingPeerLabel
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import org.koin.core.scope.Scope

/**
 * One pairing of two devices, of linked apps, driven by the shared Compose producer: this
 * device [scan]s the code of the other one, or shows its own; which of the two admits the
 * other one is not the user's choice. Main-confined.
 *
 * The wizard completes once the vault locks; once the devices are linked it shows so,
 * until the user closes it or pairs one more device. The device that showed the code
 * types the digits of the other one, with [setDigits]; the one that scanned it confirms
 * them with [approve].
 */
@Suppress("TooManyFunctions") // One Swift-facing action per wizard control.
class KeyguardPairingSession internal constructor(
    private val ctx: CoreContext,
    private val scan: Boolean,
) {
    internal companion object {
        const val FIELD_CODE = "keyguard.pairing.code"

        const val FIELD_SAS = "keyguard.pairing.sas"
    }

    internal var navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))? = null

    private val session = DetailSession<KeyguardPairingSnapshot, KeyguardPairingActions>()

    fun observe(
        onChange: (KeyguardPairingSnapshot) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable = session.observe(onChange, onClose) { publish, complete ->
        val onComplete = session.gated<Unit> { complete() }
        // The enrollment coordinator lives in the vault session scope.
        observePairing(
            name = "app_link_pair",
            publish = publish,
            onComplete = onComplete,
        ) { koin ->
            appLinkPairStateProducer(
                mode = if (scan) PairingMode.SCAN_CODE else PairingMode.SHOW_CODE,
                coordinator = koin.get(),
                getAppLinkThisDevice = koin.get(),
                getPurchased = koin.get(),
                confirmationRouteFactory = koin.get(),
                confirmAccessByPassword = koin.get(),
            )
        }
    }

    fun close() = session.close()

    /** Runs the wizard of the [producer], which gets the vault session scope. */
    private fun observePairing(
        name: String,
        publish: (KeyguardPairingSnapshot, KeyguardPairingActions) -> Unit,
        onComplete: (Unit) -> Unit,
        producer: RememberStateFlowScope.(Scope) -> Flow<Loadable<KeyguardPairState>>,
    ): KeyguardCancellable = ctx.launchSessionObserver(
        onLocked = { onComplete(Unit) },
    ) { state ->
        val sessionKoin = state.sessionKoin
        val leContext = ctx.koin.get<LeContext>()
        val producerFlow = ctx.koin
            .newHeadlessStateFlowScope(
                name,
                this,
                navigationInterceptorProvider?.invoke(sessionKoin),
            )
            .producer(sessionKoin)
        // Once linked, the wizard shows it and the user closes it, as
        // on the Compose screen; it completes on its own only on lock.
        producerFlow
            .mapNotNull { it.getOrNull() }
            .throttleLatest()
            .map { input ->
                val actions = KeyguardPairingActions(input)
                buildSnapshot(input, actions.fields, leContext) to actions
            }
            .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
    }

    private suspend fun buildSnapshot(
        state: KeyguardPairState,
        fieldHandlers: MutableMap<String, (String) -> Unit>,
        leContext: LeContext,
    ) = KeyguardPairingSnapshot(
        loaded = true,
        step = state.step?.toSnapshot(fieldHandlers, leContext),
        stage = state.step.toStagePhase().toStage(),
        pairingAsText = state.pairingAsText,
        premiumRequired = state.premiumRequired,
        canAddAnother = state.onAddAnother != null,
    )

    private suspend fun PairingStep.toSnapshot(
        fieldHandlers: MutableMap<String, (String) -> Unit>,
        leContext: LeContext,
    ): KeyguardPairingStepSnapshot = when (this) {
        PairingStep.Starting -> KeyguardPairingStepSnapshot.Starting
        is PairingStep.ShowCode -> KeyguardPairingStepSnapshot.ShowCode(
            code = code,
            expiresAtMs = expiresAt.toEpochMilliseconds(),
            expired = expired,
            // What went wrong, as the passkey screens say it.
            errorText = message ?: error?.let { textResource(it.textRes, leContext) },
        )

        is PairingStep.Scan -> KeyguardPairingStepSnapshot.Scan(
            code = code.toFieldSnapshot(fieldHandlers, id = FIELD_CODE),
            canSubmit = onSubmit != null,
        )

        PairingStep.Connecting -> KeyguardPairingStepSnapshot.Connecting
        is PairingStep.EnterSas -> KeyguardPairingStepSnapshot.EnterSas(
            peerLabel = truncatePairingPeerLabel(peerLabel),
            digits = digits.toFieldSnapshot(fieldHandlers, id = FIELD_SAS),
            canSubmit = onSubmit != null,
            isJoiner = role == EnrollmentRole.JOINER,
        )

        is PairingStep.Verify -> KeyguardPairingStepSnapshot.Verify(
            sasFormatted = formatPairingSas(sas),
            peerLabel = truncatePairingPeerLabel(peerLabel),
            canApprove = onApprove != null,
            isJoiner = role == EnrollmentRole.JOINER,
            confirmed = confirmed,
            entered = entered,
        )

        PairingStep.Transferring -> KeyguardPairingStepSnapshot.Transferring
        is PairingStep.Done -> KeyguardPairingStepSnapshot.Done(
            peerLabel = peerLabel?.let(::truncatePairingPeerLabel),
        )

        is PairingStep.Failed -> KeyguardPairingStepSnapshot.Failed(
            errorText = message ?: textResource(error.textRes, leContext),
            canRetry = onRetry != null,
        )
    }

    /** The wizard's callbacks, published atomically with the snapshot built from the same state. */
    private class KeyguardPairingActions(
        val state: KeyguardPairState,
    ) {
        val fields = LinkedHashMap<String, (String) -> Unit>()

        val step: PairingStep? get() = state.step
    }

    private fun setField(id: String, text: String) = session.withActions { it.fields[id]?.invoke(text) }

    private inline fun <reified T : PairingStep> withStep(crossinline block: (T) -> Unit) =
        session.withActions { actions -> (actions.step as? T)?.let { block(it) } }

    /** Types the code of the other device. */
    fun setCode(text: String) = setField(FIELD_CODE, text)

    /** Hands over the typed code. */
    fun submitCode() = withStep<PairingStep.Scan> { it.onSubmit?.invoke() }

    /** Hands over a code that the camera or an image produced; a wrong one shows an error instead. */
    fun submitScannedCode(text: String) = withStep<PairingStep.Scan> { it.onCode(text) }

    /** Shows a new code once the shown one can no longer be used. Swift: `doNewCode()`. */
    fun newCode() = withStep<PairingStep.ShowCode> { it.onNewCode?.invoke() }

    /** Copies the shown code, concealed. Swift: `doCopyCode()`. */
    fun copyCode() = withStep<PairingStep.ShowCode> { it.onCopy() }

    /** Types the digits the other device shows; six of them are checked at once. */
    fun setDigits(text: String) = setField(FIELD_SAS, text)

    /** Checks the typed digits. */
    fun submitDigits() = withStep<PairingStep.EnterSas> { it.onSubmit?.invoke() }

    /** The scanner's user saw the same digits on both devices. */
    fun approve() = withStep<PairingStep.Verify> { it.onApprove?.invoke() }

    /** The digits differ, or the user does not want the pairing. */
    fun reject() = session.withActions { actions ->
        when (val step = actions.step) {
            is PairingStep.Verify -> step.onReject()
            is PairingStep.EnterSas -> step.onCancel()
            else -> Unit
        }
    }

    fun retry() = withStep<PairingStep.Failed> { it.onRetry?.invoke() }

    /** Pairs one more device, in the same way, once the devices are linked. */
    fun addAnother() = session.withActions { it.state.onAddAnother?.invoke() }
}

private fun PairingStagePhase.toStage(): KeyguardPairingSnapshot.Stage = when (this) {
    PairingStagePhase.IDLE -> KeyguardPairingSnapshot.Stage.IDLE
    PairingStagePhase.STARTING -> KeyguardPairingSnapshot.Stage.STARTING
    PairingStagePhase.SHOW_CODE -> KeyguardPairingSnapshot.Stage.SHOW_CODE
    PairingStagePhase.SCAN -> KeyguardPairingSnapshot.Stage.SCAN
    PairingStagePhase.CONNECTING -> KeyguardPairingSnapshot.Stage.CONNECTING
    PairingStagePhase.VERIFY -> KeyguardPairingSnapshot.Stage.VERIFY
    PairingStagePhase.TRANSFERRING -> KeyguardPairingSnapshot.Stage.TRANSFERRING
    PairingStagePhase.DONE -> KeyguardPairingSnapshot.Stage.DONE
    PairingStagePhase.FAILED -> KeyguardPairingSnapshot.Stage.FAILED
}
