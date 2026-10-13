package com.artemchep.keyguard.desktop.passkey

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.ApplicationScope
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.passkey.WindowsPasskeyService
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.desktop.util.hasShowingWindow
import com.artemchep.keyguard.di.resolve
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

/** Windows starts the packaged app with this argument to serve a passkey request; see AppxManifest.xml. */
internal const val WINDOWS_PASSKEY_PROVIDER_ARGUMENT = "--windows-passkey-provider"

/** Covers a sign-in that follows a registration, without keeping a hidden vault for long. */
internal val WINDOWS_PASSKEY_ACTIVATION_IDLE = 30.seconds

/** A sync that runs longer, e.g. on a hung network, stops holding the process. */
internal val WINDOWS_PASSKEY_ACTIVATION_MAX_SYNC = 2.minutes

/**
 * Exits a process that Windows launched for a passkey request once it has nothing left
 * to do. Anything the user can see keeps it: any window, or the icon when it is [inTray].
 * Otherwise it waits for passkey requests and for syncs that upload their changes, so a
 * new passkey reaches the server. [onRetired] runs once Windows no longer waits on it,
 * before the process exits.
 */
@Composable
internal fun ApplicationScope.WindowsPasskeyActivationExitEffect(
    service: WindowsPasskeyService,
    getVaultSession: GetVaultSession,
    inTray: Boolean,
    onRetired: () -> Unit,
) {
    val isInTray by rememberUpdatedState(inTray)
    val onRetiredState by rememberUpdatedState(onRetired)
    LaunchedEffect(service, getVaultSession) {
        val syncing = getVaultSession.syncing()
            .trueForAtMost(WINDOWS_PASSKEY_ACTIVATION_MAX_SYNC)
        // Every window counts without being listed; the tray icon is not a window.
        val retained = combine(snapshotFlow { isInTray }, hasShowingWindow()) { tray, window -> tray || window }
        awaitWindowsPasskeyActivationEnd(
            retained = retained,
            busy = combine(service.busy, syncing) { request, sync -> request || sync },
            retire = service::retire,
        )
        // A launch acknowledged while the JVM exits would be lost; make it wait for ownership.
        withContext(Dispatchers.IO) { onRetiredState() }
        exitApplication()
    }
}

/**
 * Returns once the process was neither [retained] nor [busy] for [WINDOWS_PASSKEY_ACTIVATION_IDLE]
 * and [retire] confirmed that Windows does not wait on it. A refused retirement means a request
 * is live, so the wait starts over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun awaitWindowsPasskeyActivationEnd(
    retained: Flow<Boolean>,
    busy: Flow<Boolean>,
    retire: suspend () -> Boolean,
) {
    val idleFor = combine(retained, busy) { isRetained, isBusy -> !isRetained && !isBusy }
        .distinctUntilChanged()
        .transformLatest { isIdle ->
            if (isIdle) {
                delay(WINDOWS_PASSKEY_ACTIVATION_IDLE)
                emit(Unit)
            }
        }
    do {
        idleFor.first()
    } while (!retire())
}

/** Reads `true` for at most [duration] at a time, then `false` until the next change. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.trueForAtMost(duration: Duration): Flow<Boolean> = this
    .distinctUntilChanged()
    .transformLatest { value ->
        emit(value)
        if (value) {
            delay(duration)
            emit(false)
        }
    }

@OptIn(ExperimentalCoroutinesApi::class)
private fun GetVaultSession.syncing(): Flow<Boolean> = invoke()
    .flatMapLatest { session ->
        val supervisor = (session as? MasterSession.Key)?.session?.resolve { get<SupervisorRead>() }
        supervisor?.get(AccountTask.SYNC)?.map { it.isNotEmpty() } ?: flowOf(false)
    }
