package com.artemchep.keyguard.apple.core

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.appleKoinModules
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.impl.GetVaultSessionImpl
import com.artemchep.keyguard.common.usecase.impl.windowCoroutineExceptionHandler
import com.artemchep.keyguard.common.usecase.impl.UnlockUseCaseImpl
import com.artemchep.keyguard.platform.AppleSessionMode
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.util.foundation.crypto.ensurePlatformCryptoReady
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import com.artemchep.keyguard.common.service.session.VaultSessionLocker

/**
 * Shared kernel of the Apple bridge (macOS + iOS): the DI graph, the two coroutine scopes and the observer
 * plumbing that enforces the threading contract. Producer pipelines run on [backgroundScope]; snapshot
 * delivery and the Swift-facing handler maps hop back to the main thread through [publishOnMain]. Every
 * controller behind [KeyguardCore] routes its work through these helpers, so the contract lives in one place.
 */
internal class CoreContext(
    val runtime: KeyguardRuntime = KeyguardRuntime.APP,
) {
    // Fire-and-forget work (`putX(value).launchIn(scope)`, observer pipelines) must
    // not take the app down when it fails: report it the way the Compose apps'
    // window scope does. [backgroundScope] inherits the handler.
    val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main +
            windowCoroutineExceptionHandler { koin.get<ShowMessage>() },
    )

    val koin: Koin = run {
        // Load the native crypto backend (and fail closed on an incompatible
        // runtime) before anything can resolve a crypto service. The Compose
        // apps do this in their own entry points — `BaseApp` on Android, `main`
        // on desktop — and this is the single startup path of both Apple apps.
        ensurePlatformCryptoReady()
        koinApplication {
            // The kernel module replaces the common session locker (and, in the
            // AutoFill extension, the session and unlock use cases) with instances
            // bound to this kernel's [scope]. `IosKoinGraphTest` validates the
            // override-free platform graph.
            allowOverride(true)
            modules(appleKoinModules() + kernelModule())
        }.koin
    }

    private fun kernelModule(): Module = module {
        single<AppleSessionMode> {
            when (runtime) {
                KeyguardRuntime.APP -> AppleSessionMode.APP
                KeyguardRuntime.AUTOFILL -> AppleSessionMode.AUTOFILL
            }
        }
        // Extension graphs are disposable. Do not retain their session locker
        // in GlobalScope after a credential request has completed.
        single<VaultSessionLocker> {
            VaultSessionLocker(
                getVaultLockAfterTimeout = get(),
                clearVaultSession = get(),
                scope = this@CoreContext.scope,
            )
        }
        if (runtime == KeyguardRuntime.AUTOFILL) {
            single<GetVaultSession> {
                GetVaultSessionImpl(
                    sessionFactory = get(),
                    sessionReadWriteRepository = get(),
                    keyReadWriteRepository = get(),
                    scope = this@CoreContext.scope,
                )
            }
            single<UnlockUseCase> {
                UnlockUseCaseImpl(
                    sessionFactory = get(),
                    vaultDatabaseSessionAccess = get(),
                    biometricStatusUseCase = get(),
                    biometricKeyRepository = get(),
                    getVaultSession = get(),
                    putVaultSession = get(),
                    disableBiometric = get(),
                    logRepository = get(),
                    keyReadWriteRepository = get(),
                    sessionMetadataReadWriteRepository = get(),
                    getBiometricRequireConfirmation = get(),
                    getBiometricRemainingDuration = get(),
                    biometricKeyEncryptUseCase = get(),
                    decryptBiometricKeyUseCase = get(),
                    authConfirmMasterKeyUseCase = get(),
                    authGenerateMasterKeyUseCase = get(),
                    cryptoGenerator = get(),
                    cipherEncryptor = get(),
                    yubiKeyUnlockAvailability = get(),
                    fido2UnlockAvailability = get(),
                    fido2UnlockService = get(),
                    base64Service = get(),
                    scope = this@CoreContext.scope,
                )
            }
            // Never restore the app's persisted master key into an extension request.
            // Password or biometric verification must happen in the presented UI.
            single<KeyReadWriteRepository> {
                AutofillSessionRepository()
            }
        }
    }

    /**
     * The same supervisor job as [scope] on [Dispatchers.Default], so the headless producers' flow machinery
     * (search, sorting, filtering, counters) stays off the main thread — matching the Default-dispatcher
     * scope the Compose `FlowHolderViewModel` gives the same producers.
     */
    val backgroundScope = scope + Dispatchers.Default

    val unlockUseCase: UnlockUseCase by lazy { koin.get() }

    /**
     * Mirrors the effective SwiftUI appearance so headless producers that read the Compose color scheme
     * (the attachment preview's syntax highlighting) pick the matching palette. Writes go through the
     * core [scope] so Compose snapshot state never races the producers.
     */
    val interfaceColorSchemeState = mutableStateOf(lightColorScheme())

    /**
     * Runs on [backgroundScope]. The pipeline must route its `onChange` callbacks — and any handler-map side
     * effects the Swift-facing `invoke*` methods read — through [publishOnMain], which keeps that state
     * main-confined.
     */
    fun launchObserver(
        block: suspend CoroutineScope.() -> Unit,
    ): KeyguardCancellable {
        val job = backgroundScope.launch(block = block)
        return KeyguardCancellable(job)
    }

    /**
     * The final stage of an observer pipeline: snapshot delivery plus the handler-map mutations, which must
     * stay main-confined because Swift reads them from main-thread calls.
     */
    suspend fun <T> publishOnMain(
        block: suspend CoroutineScope.() -> T,
    ): T = withContext(Dispatchers.Main, block)

    /** The current settled vault state, skipping the transient [VaultState.Loading]. */
    suspend fun currentState(): VaultState =
        unlockUseCase().first { it !is VaultState.Loading }

    suspend fun awaitMain(): VaultState.Main =
        unlockUseCase().first { it is VaultState.Main } as VaultState.Main

    /**
     * While the vault is unlocked, runs [block] in a child scope (cancelled, and [block] re-run, on every
     * vault-state change); otherwise runs [onLocked] on the main thread — reset the screen's `latest*` /
     * handler-map fields there and emit the empty snapshot. [onTeardown] runs on the main thread once the
     * observer stops, cancellation included.
     */
    fun launchSessionObserver(
        onLocked: suspend () -> Unit,
        onTeardown: (suspend () -> Unit)? = null,
        block: suspend CoroutineScope.(VaultState.Main) -> Unit,
    ): KeyguardCancellable = launchObserver {
        try {
            unlockUseCase().collectLatest { state ->
                if (state is VaultState.Main) {
                    coroutineScope {
                        block(state)
                    }
                } else {
                    publishOnMain {
                        onLocked()
                    }
                }
            }
        } finally {
            if (onTeardown != null) {
                // The main hop must survive the cancelled job.
                withContext(NonCancellable + Dispatchers.Main) {
                    onTeardown()
                }
            }
        }
    }
}

/**
 * Terminal operator for observer pipelines: conflates the upstream — a burst of
 * emissions while a main-thread hop is in flight collapses into the latest value
 * — and runs [publish] on the main thread. [publish] is the main-confined stage:
 * the `latest*` / handler-map assignments plus the `onChange` delivery. Keep
 * heavy snapshot building upstream (in a `map` stage, or a plain `collect` body
 * before a [CoreContext.publishOnMain] tail) so it stays off the main thread.
 */
internal suspend fun <T> Flow<T>.collectOnMain(
    publish: suspend (T) -> Unit,
) = conflate().collect { value ->
    withContext(Dispatchers.Main) { publish(value) }
}
