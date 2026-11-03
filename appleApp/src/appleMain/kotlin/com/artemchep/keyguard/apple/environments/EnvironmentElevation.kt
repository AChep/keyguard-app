package com.artemchep.keyguard.apple.environments

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.environment.EnvironmentAccess
import com.artemchep.keyguard.common.service.environment.ensureElevatedAccess
import com.artemchep.keyguard.feature.environments.ui.verifyEnvironmentAccessIfHandled
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.core.scope.Scope

internal suspend fun CoreContext.verifyEnvironmentAccess(
    interceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean),
): Boolean {
    val state = currentState() as? VaultState.Main ?: return false
    return publishOnMain {
        verifyEnvironmentAccessIfHandled { intent ->
            interceptorProvider(state.sessionKoin)(intent)
        }
    }
}

/**
 * Resolves the approval request [id]. Approving it first lets [confirm]
 * verify the user's identity, if the request requires it; an error denies it.
 */
internal fun CoreContext.resolveApprovalRequest(
    id: String,
    approved: Boolean,
    interceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean),
    confirm: suspend (verify: suspend () -> Boolean) -> Boolean,
    respond: (id: String, approved: Boolean) -> Boolean,
) {
    if (!approved) {
        respond(id, false)
        return
    }
    scope.launch {
        try {
            val confirmed = confirm {
                verifyEnvironmentAccess(interceptorProvider)
            }
            respond(id, confirmed)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            respond(id, false)
        }
    }
}

internal fun CoreContext.resolveEnvironmentRequest(
    id: String,
    approved: Boolean,
    interceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean),
) {
    val service = koin.get<EnvironmentAccess>()
    resolveApprovalRequest(
        id = id,
        approved = approved,
        interceptorProvider = interceptorProvider,
        confirm = { verify ->
            val request = service.approvals.value.firstOrNull { it.id == id }
            request != null && service.ensureElevatedAccess(request, verify)
        },
        respond = service::respondToApproval,
    )
}
