package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.AddCredentialCipher
import com.artemchep.keyguard.common.usecase.ClearDataHook
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.ReconnectBackoff
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.passkey_provider_error
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyBridge
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyFailure
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyIdentity
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyStatus
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getString

/** Resolved at the composition boundary; the service itself has no dependency-container access. */
fun interface WindowsPasskeyVaultAccess {
    fun get(session: MasterSession.Key): WindowsPasskeyVault
}

data class WindowsPasskeyVault(
    val getCiphers: GetCiphers,
    val getProfiles: GetProfiles,
    val addCredential: AddCredentialCipher,
    val getCanWrite: GetCanWrite,
    val getCollections: GetCollections,
    val recordUsage: AddCipherUsedPasskeyHistory,
) {
    /**
     * The items Windows may offer to sign in with. Like the vault and the other platforms,
     * it never offers hidden profiles; their passkeys are still stored, so [getCiphers]
     * checks them against a site's excluded credentials.
     */
    fun getVisibleCiphers(): Flow<List<DSecret>> = filterHiddenProfiles(getCiphers, getProfiles)
}

/** Keeps Windows integration alive when every application window is hidden. */
class WindowsPasskeyService(
    private val getVaultSession: GetVaultSession,
    private val vaultAccess: WindowsPasskeyVaultAccess,
    vaultSessionLocker: VaultSessionLocker,
    authenticator: WebAuthnAuthenticator,
    private val showMessage: ShowMessage,
    private val bridge: WindowsPasskeyBridge,
    private val scope: CoroutineScope,
) : ClearDataHook {
    private companion object {
        /** A failing index update retries after this, doubling up to [CACHE_RETRY_MAX_MS]. */
        const val CACHE_RETRY_MS = 5000L
        const val CACHE_RETRY_MAX_MS = 5 * 60_000L

        /** Also refreshes the status when Windows does not report its changes. */
        val EVENT_TIMEOUT = 30.seconds

        /** While Windows reports status changes, this only covers a report that it missed. */
        val MISSED_CHANGE_TIMEOUT = 5.minutes
    }

    private val ceremony = WindowsPasskeyCeremony(
        bridge = bridge,
        getVaultSession = getVaultSession,
        vaultAccess = vaultAccess,
        vaultSessionLocker = vaultSessionLocker,
        authenticator = authenticator,
        onFailure = ::reportFailure,
    )
    private val mutableStatus = MutableStateFlow(WindowsPasskeyStatus.UNAVAILABLE)
    val status = mutableStatus.asStateFlow()
    val ui = ceremony.ui
    val busy = ceremony.busy
    private val mutableCacheFailed = MutableStateFlow(false)
    val cacheFailed = mutableCacheFailed.asStateFlow()

    // Erasing data stops the index sync from another thread.
    private val metadataLock = Mutex()
    private var metadataJob: Job? = null

    fun start() {
        scope.launch {
            val initial = readStatus()
            mutableStatus.value = initial
            // Packaging and OS support cannot change while the process runs.
            if (initial == WindowsPasskeyStatus.UNSUPPORTED || initial == WindowsPasskeyStatus.UNPACKAGED) {
                return@launch
            }
            metadataLock.withLock { launchMetadataSync() }
            handleEvents()
        }
    }

    /**
     * Waits natively for requests and status changes; cancellation ends the wait. Windows only
     * sends requests while enabled.
     */
    private suspend fun handleEvents() = coroutineScope {
        var operation: WindowsPasskeyOperation? = null
        var timeout = EVENT_TIMEOUT
        try {
            while (isActive) {
                if (operation == null) {
                    // Windows sends an unregistered provider neither requests nor status changes.
                    // Only setRegistered can register it, and that updates the status directly.
                    status.first { it != WindowsPasskeyStatus.UNREGISTERED }
                }
                val event = attempt { bridge.next(operation?.id, timeout) }
                when {
                    event == null -> {
                        // Likely not serving, e.g. the start at launch failed: start again, and
                        // keep the status current without spinning.
                        attempt { bridge.serve() }
                        mutableStatus.value = readStatus()
                        delay(EVENT_TIMEOUT)
                    }

                    event.closed -> break

                    else -> {
                        // Reported status changes end the wait.
                        timeout = if (event.watching) MISSED_CHANGE_TIMEOUT else EVENT_TIMEOUT
                        if (operation?.id != event.activeId) {
                            operation?.cancelIfPending()
                            operation = null
                        }
                        val request = event.request
                        if (request != null) {
                            operation = WindowsPasskeyOperation(request.id, this) { pending ->
                                ceremony.handle(request, pending)
                            }.also { it.start() }
                        } else {
                            // A status change, a finished request, or the timeout.
                            mutableStatus.value = readStatus()
                        }
                    }
                }
            }
        } finally {
            operation?.cancel()
        }
    }

    private suspend fun readStatus() = attempt { bridge.readStatus() } ?: WindowsPasskeyStatus.UNAVAILABLE

    /** Callers hold [metadataLock]. */
    private fun launchMetadataSync() {
        metadataJob = scope.launch { syncMetadata() }
    }

    private suspend fun syncMetadata() {
        // Restart only when registration changes. Enabling does not affect the index, and a
        // failed status read means unknown, not unregistered.
        val registered = status
            .filter { it != WindowsPasskeyStatus.UNAVAILABLE }
            .map { it.isRegistered }
            .distinctUntilChanged()
        combine(getVaultSession(), registered, ::Pair)
            .collectLatest { (session, isRegistered) ->
                if (!isRegistered) return@collectLatest
                attempt { bridge.setLocked(session !is MasterSession.Key) }
                if (session !is MasterSession.Key) return@collectLatest
                // A persistent OS or vault failure retries less often; any successful sync resets it.
                val retry = ReconnectBackoff(baseDelayMs = CACHE_RETRY_MS, maxDelayMs = CACHE_RETRY_MAX_MS)
                while (session.session.active.value) {
                    attempt {
                        vaultAccess.get(session).getVisibleCiphers()
                            .windowsPasskeyIndex()
                            .collectLatest { identities -> syncIndex(identities, session, retry) }
                    }
                    // Retry a failed vault/index read without stopping request handling.
                    mutableCacheFailed.value = true
                    delay(retry.nextDelayMs())
                }
            }
    }

    /** Pushes the [identities] to Windows, retrying while the [session] stays active. */
    private suspend fun syncIndex(
        identities: List<WindowsPasskeyIdentity>,
        session: MasterSession.Key,
        retry: ReconnectBackoff,
    ) {
        do {
            val success = attempt { bridge.sync(identities) } != null
            mutableCacheFailed.value = !success
            if (success) {
                retry.reset()
                return
            }
            delay(retry.nextDelayMs())
        } while (session.session.active.value)
    }

    // Native loading, OS API and serialization failures must leave the settings UI usable.
    fun setRegistered(registered: Boolean): IO<Unit> = ioEffect {
        val result = runCatchingNonFatal {
            if (registered) bridge.register() else bridge.unregister()
            mutableStatus.value = bridge.readStatus()
        }
        if (result.isFailure) reportFailure()
    }

    /** The user dismissed the request. */
    fun cancel(id: Long): IO<Unit> = ioEffect {
        attempt { bridge.complete(id, null, WindowsPasskeyFailure.DISMISSED) }
        Unit
    }

    /**
     * Stops serving Windows, unless it still waits on a request. The process should exit
     * afterwards: Windows starts a new one for the next request. A broken bridge serves
     * nothing, so it never keeps the process alive.
     */
    suspend fun retire(): Boolean = attempt { bridge.retire() } ?: true

    /**
     * Clear the OS index before erasing the vault. A registered provider's failures can be
     * retried; an unreachable provider must never prevent the user from erasing their data.
     */
    @Suppress("TooGenericExceptionCaught") // Resume indexing if erasing cannot proceed.
    override suspend fun beforeClearData() {
        metadataLock.withLock {
            // Joining also waits for a native sync in progress, so it cannot refill the index.
            metadataJob?.cancelAndJoin()
            metadataJob = null
            val state = readStatus()
            when {
                state.isRegistered -> try {
                    bridge.clear()
                } catch (e: Exception) {
                    launchMetadataSync()
                    throw e
                }
                // The native bridge or the OS API is broken. It may still hold an index, so
                // try once, but do not block erasing on it.
                state == WindowsPasskeyStatus.UNAVAILABLE -> attempt { bridge.clear() }
                // Unsupported, unpackaged or unregistered: there is no index to clear.
                else -> Unit
            }
        }
    }

    private suspend fun reportFailure() {
        showMessage.copy(
            ToastMessage(type = ToastMessage.Type.ERROR, title = getString(Res.string.passkey_provider_error)),
        )
    }
}
