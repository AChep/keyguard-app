package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.model.MasterSession
import kotlinx.coroutines.flow.Flow

/**
 * Denies the pending prompts whenever an unlocked vault session ends: a
 * lock means the user is leaving, and an approval given after the next
 * unlock would act on a caller context from before it. A prompt shown
 * while the vault is locked survives its unlock, so the user can unlock
 * and answer it.
 *
 * Every agent applies this rule through here, so the platforms agree.
 */
suspend fun AgentApprovalBroker<*>.denyWhenSessionEnds(sessions: Flow<MasterSession>) {
    var previousSession: MasterSession.Key? = null
    sessions.collect { session ->
        if (previousSession != null && previousSession !== session) denyAll()
        previousSession = session as? MasterSession.Key
    }
}
