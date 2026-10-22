package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * Reaches the environments of the unlocked vault. Knows nothing about
 * who may access them: see [EnvironmentAccessImpl].
 */
class EnvironmentVault(
    private val getVaultSession: GetVaultSession,
    private val sessionAccess: EnvironmentSessionAccess,
) {
    /** Serializes the writes to the environments and to the local `.env` files. */
    internal val writeLock = Mutex()

    internal fun currentKey(): MasterSession.Key =
        (getVaultSession.valueOrNull as? MasterSession.Key)
            ?.takeIf { it.session.active.value }
            ?: throw EnvironmentServiceException("vault_locked")

    internal fun sessionIdentity(): String = currentKey().session.id

    internal fun checkSession(identity: String) {
        if (currentKey().session.id != identity) throw EnvironmentServiceException("vault_locked")
    }

    /** Runs the [block] in the unlocked session; fails if the session changes meanwhile. */
    internal suspend fun <T> withVault(block: suspend (EnvironmentSessionDependencies) -> T): T {
        checkEnvironmentOperation()
        val guard = currentCoroutineContext()[EnvironmentOperationGuard]
        val session = currentKey()
        val dependencies = sessionAccess(session) ?: throw EnvironmentServiceException("vault_locked")
        val task = dependencies.scope.async(guard ?: EmptyCoroutineContext) {
            checkEnvironmentOperation()
            checkSession(session.session.id)
            val result = block(dependencies)
            checkEnvironmentOperation()
            checkSession(session.session.id)
            result
        }
        return try {
            task.await()
        } catch (e: CancellationException) {
            // A lock cancels the session scope, not the caller.
            currentCoroutineContext().ensureActive()
            throw if (dependencies.scope.isActive) e else EnvironmentServiceException("vault_locked")
        } finally {
            task.cancel()
        }
    }
}

internal suspend fun EnvironmentSessionDependencies.environmentSecret(id: String): DSecret = getCiphers().first()
    .firstOrNull { it.id == id && it.type == DSecret.Type.Environment && it.deletedDate == null }
    ?: throw EnvironmentServiceException("not_found")
