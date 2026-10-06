package com.artemchep.keyguard.desktop.services.autotype

import arrow.core.throwIfFatal
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.home.vault.quicksearch.QuickSearchAutotypePayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Owns the operation outside the popup lifecycle, but never outside the unlocked vault lifetime. */
internal class QuickSearchAutotypeController(
    private val service: AutotypeService,
    private val getVaultSession: GetVaultSession,
    private val scope: CoroutineScope,
    private val onFailure: suspend (AutotypeResult) -> Unit,
) {
    private val target = AtomicLong(0)
    private val revision = AtomicLong(0)
    private var job: Job? = null

    // Called by the global hotkey before any Keyguard window receives focus.
    fun captureTarget() {
        revision.incrementAndGet()
        target.set(0)
        // Native loading failures must not prevent opening Quick Search.
        target.set(runCatching(service::captureTarget).getOrElse { e -> e.throwIfFatal(); 0L })
    }

    // UI-thread entry point. Repeated shortcut key-downs never queue credentials.
    // The window callback reports whether the popup is out of the way.
    @Suppress("TooGenericExceptionCaught") // Surface a redacted failure for native/OS errors.
    fun start(payload: QuickSearchAutotypePayload, sessionId: String, hideWindow: suspend () -> Boolean) {
        if (job?.isActive == true) return
        val token = target.getAndSet(0)
        val invocation = revision.get()
        job = scope.launch {
            val result = try {
                perform(token, payload, sessionId, invocation, hideWindow)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.throwIfFatal()
                AutotypeResult.InputFailed
            }
            // Cancellation leaves focus where the user put it.
            if (result == AutotypeResult.Success || result == AutotypeResult.Interrupted) return@launch
            // An old operation must not reopen or reset a newer popup invocation.
            if (revision.get() == invocation) onFailure(result)
        }
    }

    private suspend fun perform(
        token: Long,
        payload: QuickSearchAutotypePayload,
        sessionId: String,
        invocation: Long,
        hideWindow: suspend () -> Boolean,
    ): AutotypeResult {
        if (!service.requestPermission()) return AutotypeResult.PermissionRequired
        val session = (getVaultSession().first() as? MasterSession.Key)?.session
        return when {
            session == null || session.id != sessionId || !session.active.value -> AutotypeResult.Interrupted
            revision.get() != invocation -> AutotypeResult.Interrupted
            token == 0L -> AutotypeResult.Unavailable
            else -> {
                val context = currentCoroutineContext()
                val active = {
                    context.isActive && session.active.value && revision.get() == invocation
                }
                val login = payload()
                when {
                    !active() -> AutotypeResult.Interrupted
                    login == null -> AutotypeResult.InputFailed
                    !hideWindow() -> AutotypeResult.Unavailable
                    else -> service.typeLogin(token, login, active).bind()
                }
            }
        }
    }
}
