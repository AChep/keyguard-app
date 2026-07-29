package com.artemchep.keyguard.android.credentialexchange

import android.os.Build
import androidx.annotation.RequiresApi
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountRegistration
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountRepository
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.worker.Wrker
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.onState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * Keeps Keyguard's credential-exchange export registration in sync with the exposed
 * database's account mirror: advertises every mirrored account, and unregisters only
 * once the mirror holds none.
 *
 * The mirror, and not the vault session, is the source on purpose. This worker is
 * driven by the process lifecycle, so it runs from the moment ANY activity starts —
 * the unlock screen, an autofill or passkey activity, and
 * [CredentialExportActivity] itself, which the platform launches into a locked
 * process. A locked vault is therefore not evidence that there is nothing to
 * advertise, and treating it as such used to drop the registration out of the system
 * picker while the user was still looking at the export consent screen.
 *
 * The mirror is readable while locked and is rewritten from the profiles on every
 * unlocked sync, so a removed or hidden account is still caught — just while the
 * vault is open. It is also the exact snapshot [CredentialExportActivity] resolves
 * the picked entry against, so what is advertised and what can be answered can no
 * longer disagree.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CredentialExchangeRegistrationWorker internal constructor(
    registry: CredentialExchangeRegistrationBackend,
    private val exposedAccountRepository: ExposedAccountRepository,
    private val logRepository: LogRepository,
) : Wrker {
    private val targetApplier = CredentialExchangeRegistrationTargetApplier(registry)

    constructor(
        directDI: DirectDI,
    ) : this(
        registry = directDI.instance<CredentialExchangeRegistry>(),
        exposedAccountRepository = directDI.instance(),
        logRepository = directDI.instance(),
    )

    override fun start(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    ) {
        flow
            .onState {
                val targets = exposedAccountRepository.getRegistrations()
                    .map { registrations ->
                        credentialExchangeRegistrationTarget(registrations)
                    }
                    // Reading the mirror is the first thing this worker does, and it
                    // now happens on every process start rather than only after an
                    // unlock — so a failing read must not take the lifecycle scope
                    // down with it. Emitting nothing leaves the applier's last
                    // successful target in place, which keeps an existing
                    // registration standing: the same reasoning as sourcing the
                    // mirror at all, since an unreadable mirror is no more evidence
                    // that there is nothing to advertise than a locked vault was.
                    .catch { e ->
                        e.throwIfFatalOrCancellation()
                        logRepository.post(
                            tag = TAG,
                            message = "Failed to read the account mirror: ${e.message}",
                            level = LogLevel.ERROR,
                        )
                    }
                targetApplier.collect(targets)
            }
            .launchIn(scope)
    }

    private companion object {
        private const val TAG = "CredentialExchangeRegistration"
    }
}

internal sealed interface CredentialExchangeRegistrationTarget {
    data object Cleared : CredentialExchangeRegistrationTarget

    data class Ready(
        val registrations: List<ExposedAccountRegistration>,
    ) : CredentialExchangeRegistrationTarget
}

/**
 * What a committed mirror snapshot means for the platform registration.
 *
 * An empty snapshot is the only thing that unregisters. The mirror holds exactly the
 * accounts that may be advertised — hidden ones are left out of it, and it is
 * rewritten wholesale on every unlocked sync — so it is empty only when there is
 * genuinely nothing to offer, never merely because the vault is locked.
 */
internal fun credentialExchangeRegistrationTarget(
    registrations: List<ExposedAccountRegistration>,
): CredentialExchangeRegistrationTarget = if (registrations.isEmpty()) {
    CredentialExchangeRegistrationTarget.Cleared
} else {
    CredentialExchangeRegistrationTarget.Ready(registrations)
}

/**
 * Applies provider updates one at a time and remembers only successful effects.
 *
 * Provider registration is a remote side effect. Once submitted it is allowed to
 * finish even if the lifecycle collector is cancelled, otherwise a restarted
 * collector could issue a newer request while the abandoned older request is
 * still completing remotely.
 */
internal class CredentialExchangeRegistrationTargetApplier(
    private val registry: CredentialExchangeRegistrationBackend,
) {
    private var appliedTarget: CredentialExchangeRegistrationTarget? = null

    suspend fun collect(
        targets: Flow<CredentialExchangeRegistrationTarget>,
    ) {
        targets
            .distinctUntilChanged()
            .conflate()
            .collect { target ->
                if (target == appliedTarget) {
                    return@collect
                }

                withContext(NonCancellable) {
                    val applied = when (target) {
                        CredentialExchangeRegistrationTarget.Cleared ->
                            registry.unregister()

                        is CredentialExchangeRegistrationTarget.Ready ->
                            registry.register(target.registrations)
                    }
                    if (applied) {
                        appliedTarget = target
                    }
                }
            }
    }
}
