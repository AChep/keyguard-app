package com.artemchep.keyguard.common.service.session

import com.artemchep.autotype.DesktopPowerEvent
import com.artemchep.autotype.DesktopPowerRegistrationResult
import com.artemchep.autotype.registerDesktopPowerEvents
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_failed_power_lock_start
import com.artemchep.keyguard.res.lock_reason_screen_off
import com.artemchep.keyguard.res.lock_reason_system_sleep
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.getString
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class VaultPowerLockService(
    private val getVaultLockAfterScreenOff: GetVaultLockAfterScreenOff,
    private val sessionRepository: SessionReadWriteRepository,
    private val clearVaultSession: ClearVaultSession,
    private val showMessage: ShowMessage,
    private val platform: Platform = CurrentPlatform,
    private val register: ((DesktopPowerEvent) -> Unit, (Throwable) -> Unit) -> DesktopPowerRegistrationResult =
        ::registerDesktopPowerEvents,
) {
    suspend fun run(): Unit = coroutineScope {
        if (platform !is Platform.Desktop.MacOS) return@coroutineScope

        // Resource reads and preference initialization happen before installing
        // the callback. Its lock path only reads memory and replaces the session.
        val displayReason = TextHolder.Value(getString(Res.string.lock_reason_screen_off))
        val sleepReason = TextHolder.Value(getString(Res.string.lock_reason_system_sleep))
        val failureMessage = getString(Res.string.error_failed_power_lock_start)
        val enabled = getVaultLockAfterScreenOff().stateIn(this)
        val handler = VaultPowerLockHandler(
            isEnabled = enabled::value,
            // Read the repository directly: GetVaultSession's shared replay cache
            // may still contain the old unlocked session immediately after a lock.
            getSession = { sessionRepository.get().first() },
            clearVaultSession = clearVaultSession,
            displayReason = displayReason,
            sleepReason = sleepReason,
            onError = ::recordPowerLockFailure,
        )
        val result = register(handler::onEvent, ::recordPowerLockFailure)
        val registration = when (result) {
            is DesktopPowerRegistrationResult.Success -> result.registration
            is DesktopPowerRegistrationResult.Failure -> {
                recordLog("Vault power lock registration failed: ${result.reason}.")
                result.cause?.let(::recordPowerLockFailure)
                showMessage.copy(ToastMessage(type = ToastMessage.Type.ERROR, title = failureMessage))
                null
            }
        }
        try {
            awaitCancellation()
        } finally {
            handler.stop()
            if (registration != null && !registration.unregister()) {
                recordLog("Vault power lock unregistration failed.")
            }
        }
    }
}

private fun recordPowerLockFailure(error: Throwable) {
    recordLog("Vault power lock failed: ${error::class.simpleName}.")
    recordException(error)
}

internal class VaultPowerLockHandler(
    private val isEnabled: () -> Boolean,
    private val getSession: suspend () -> MasterSession?,
    private val clearVaultSession: ClearVaultSession,
    private val displayReason: TextHolder,
    private val sleepReason: TextHolder,
    private val onError: (Throwable) -> Unit,
    private val timeoutMillis: Long = 1_000L,
) {
    /** A lock attempt that failed and may be retried on the next wake. */
    private class Pending(
        session: MasterSession.Key,
        val reason: TextHolder,
    ) {
        // A failed lock must not keep an otherwise discarded master key alive.
        val session = WeakReference(session)
    }

    private val stopped = AtomicBoolean(false)
    private var pending: Pending? = null

    fun stop() {
        stopped.set(true)
    }

    @Synchronized
    fun onEvent(event: DesktopPowerEvent) {
        if (stopped.get()) return
        try {
            runBlocking {
                withTimeout(timeoutMillis) {
                    val session = if (isEnabled()) getSession() as? MasterSession.Key else null
                    if (session == null) {
                        pending = null
                        return@withTimeout
                    }
                    val reason = when (event) {
                        DesktopPowerEvent.DisplaySleep -> displayReason
                        DesktopPowerEvent.SystemSleep -> sleepReason
                        DesktopPowerEvent.DisplayWake, DesktopPowerEvent.SystemWake -> {
                            // Retry a failed lock only against the session it targeted.
                            val retry = pending?.takeIf { it.session.get() === session }
                            pending = null
                            retry?.reason ?: return@withTimeout
                        }
                    }
                    pending = Pending(session, reason)
                    clearVaultSession(LockReason.TIMEOUT, reason).bind()
                    pending = null
                }
            }
        } catch (e: Throwable) {
            runCatching { onError(e) }
        }
    }
}
