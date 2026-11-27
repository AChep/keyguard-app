package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.completeOnPop
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The subscription of a headless form session that completes when the producer pops
 * itself. [present] maps each state off the main thread; the result is published on it.
 */
internal fun <State : Any, Snapshot, Actions> CoreContext.subscribeHeadlessForm(
    name: String,
    produce: suspend RememberStateFlowScope.() -> Flow<Loadable<State>>,
    present: (State?) -> Pair<Snapshot, Actions>,
): (publish: (Snapshot, Actions) -> Unit, complete: () -> Unit) -> KeyguardCancellable = { publish, complete ->
    launchObserver {
        koin.newHeadlessStateFlowScope(name, this, completeOnPop(scope, complete))
            .produce()
            .throttleLatest()
            .map { loadable -> present(loadable.getOrNull()) }
            .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
    }
}
