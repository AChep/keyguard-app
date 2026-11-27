package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.auth.AuthPromptHost
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.completeOnPop
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.changepassword.ChangePasswordState
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * A controller of a headless form that biometric-unlock users confirm through the producer's
 * `sideEffects.showBiometricPromptFlow` (the password and key derivation changes): without
 * collecting it, the prompt never appears and the change hangs.
 */
internal abstract class BiometricConfirmedFormController(
    protected val ctx: CoreContext,
    private val authPromptHost: AuthPromptHost,
) {
    /**
     * The subscription of a form session. The prompt flow is the same EventFlow for the whole
     * producer lifetime, so it is collected once. Each prompt goes through the [AuthPromptHost],
     * which runs Touch ID / Face ID and then invokes the prompt's `onComplete` on the main
     * thread, or delivers the error on cancel or failure. [present] maps each state off the
     * main thread; the result is published on it.
     */
    protected fun <State : Any, Snapshot, Actions> subscribe(
        name: String,
        promptReason: StringResource,
        sideEffects: (State) -> ChangePasswordState.SideEffects,
        produce: suspend RememberStateFlowScope.() -> Flow<Loadable<State>>,
        present: (State?) -> Pair<Snapshot, Actions>,
    ): (publish: (Snapshot, Actions) -> Unit, complete: () -> Unit) -> KeyguardCancellable = { publish, complete ->
        val leContext = ctx.koin.get<LeContext>()
        ctx.launchObserver {
            var promptCollectorStarted = false
            ctx.koin.newHeadlessStateFlowScope(name, this, completeOnPop(ctx.scope, complete))
                .produce()
                .throttleLatest()
                .map { loadable ->
                    val state = loadable.getOrNull()
                    if (state != null && !promptCollectorStarted) {
                        promptCollectorStarted = true
                        val promptFlow = sideEffects(state).showBiometricPromptFlow
                        val reason = textResource(promptReason, leContext)
                        launch {
                            promptFlow.collect { prompt ->
                                authPromptHost.handleBiometricPrompt(prompt, reason = reason)
                            }
                        }
                    }
                    present(state)
                }
                .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
        }
    }
}
