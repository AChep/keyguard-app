package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.vault.VaultDatabaseSessionAccess
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.service.vault.VaultSessionFactory
import kotlin.uuid.Uuid
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.core.scope.ScopeCallback
import org.koin.dsl.module

/** Type-qualified scope; its definitions are installed with the application graph. */
class VaultSessionScope private constructor()

class VaultScopeSource(val masterKey: MasterKey) {
    val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

interface KeyguardKoinOwner {
    val koin: Koin
}

private class KoinVaultSession(private val delegate: Scope) : VaultSession {
    private val lock = SynchronizedObject()
    private val mutableActive = MutableStateFlow(true)
    private var closed = false
    override val id: String = delegate.id
    override val active = mutableActive.asStateFlow()

    init {
        delegate.registerCallback(object : ScopeCallback {
            override fun onScopeClose(scope: Scope) {
                // Application shutdown can close a scope without going through
                // the session owner. Publish retirement in that case as well.
                mutableActive.value = false
                scope.getSource<VaultScopeSource>()?.coroutineScope?.cancel()
            }
        })
    }

    fun <T> resolve(block: Scope.() -> T): T? = synchronized(lock) {
        if (mutableActive.value) delegate.block() else null
    }

    override fun retire() = synchronized(lock) {
        mutableActive.value = false
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            mutableActive.value = false
            delegate.getSource<VaultScopeSource>()?.coroutineScope?.cancel()
            delegate.close()
        }
    }
}

/** Only DI, UI, and framework adapters may resolve through the container. */
fun <T> VaultSession.resolve(block: Scope.() -> T): T? =
    (this as? KoinVaultSession)?.resolve(block)

/** Framework/UI boundary. Resolves from an active session, or cancels the caller when it is retired. */
fun <T> VaultSession.resolveOrCancel(block: Scope.() -> T): T =
    resolve(block) ?: throw CancellationException("Vault session retired")

/** Framework/UI boundary. Call on the UI dispatcher after checking the active session. */
internal val VaultSession.scope: Scope
    get() = checkNotNull(resolve { this }) { "Vault session is retired: $id" }

/** Also allows isolated test graphs to exercise the real retirement behavior. */
internal fun Scope.asVaultSession(): VaultSession = KoinVaultSession(this)

internal class KoinVaultSessionFactory(private val koin: Koin) : VaultSessionFactory {
    // Any failure of the container has to release the scope source; the cause is rethrown as is.
    @Suppress("TooGenericExceptionCaught")
    override fun create(masterKey: MasterKey): VaultSession {
        val source = VaultScopeSource(masterKey)
        try {
            return koin.createScope(
                scopeId = Uuid.random().toString(),
                qualifier = named<VaultSessionScope>(),
                source = source,
            ).asVaultSession()
        } catch (e: Throwable) {
            source.coroutineScope.cancel()
            throw e
        }
    }

    // Any failure of the unlock, cancellation included, has to close the session; the cause is rethrown as is.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun createAuthenticated(masterKey: MasterKey): VaultSession {
        val session = create(masterKey)
        try {
            checkNotNull(session.resolve { get<VaultDatabaseManager>() }).get().bind()
            currentCoroutineContext().ensureActive()
            return session
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    }
}

internal class VaultSessionLifecycleModule {
    val module = module {
        single<VaultSessionFactory> { KoinVaultSessionFactory(getKoin()) }
        single<VaultDatabaseSessionAccess> {
            VaultDatabaseSessionAccess { session -> session.resolve { get<VaultDatabaseManager>() } }
        }
    }
}
