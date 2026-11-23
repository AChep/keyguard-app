package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.onState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Keeps temporary system prompts distinct from leaving the application. */
internal class AppSceneLifecycle(
    initialPhase: KeyguardScenePhase,
    private val onReturnFromBackground: () -> Unit,
) {
    private val mutableState = MutableStateFlow(initialPhase.toLifecycleState())
    val state = mutableState.asStateFlow()
    private var refreshOnActivation = false

    fun setPhase(phase: KeyguardScenePhase) {
        if (phase == KeyguardScenePhase.BACKGROUND) refreshOnActivation = true
        if (phase == KeyguardScenePhase.ACTIVE && refreshOnActivation) {
            refreshOnActivation = false
            onReturnFromBackground()
        }
        mutableState.value = phase.toLifecycleState()
    }
}

/**
 * Process lifetime, including a startup that has not created a window yet. A foreground
 * start arms the lock timer only when its first window releases the session, so a slow
 * cold start cannot time out early.
 */
internal fun startAppleSessionLifetime(
    scope: CoroutineScope,
    lifecycle: StateFlow<LeLifecycleState>,
    locker: VaultSessionLocker,
): Job {
    if (lifecycle.value < LeLifecycleState.RESUMED) locker.start()
    return scope.launch {
        lifecycle.onState(minActiveState = LeLifecycleState.RESUMED) {
            locker.keepAlive()
        }.collect()
    }
}
