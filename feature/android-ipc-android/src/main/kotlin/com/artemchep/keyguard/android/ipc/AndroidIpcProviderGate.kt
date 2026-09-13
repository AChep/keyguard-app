package com.artemchep.keyguard.android.ipc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Process-wide mirror of the two IPC provider enable switches.
 *
 * The durable gate is the manifest component state: both services declare
 * `android:enabled="false"` and `installAndroidIpcProviders` toggles them to
 * match the preference, so the system never routes a bind to a provider the
 * user turned off. This mirror exists for the window between the preference
 * flipping and the `setComponentEnabledSetting` round-trip landing, and is
 * therefore only consulted per request — never from `Service.onBind`, whose
 * null return would be cached by system_server for the life of the service.
 *
 * A provider whose state is not known yet is *unknown*, not disabled: the cold
 * encrypted-DataStore read behind the preference flows can still be in flight
 * when a bind arrives and creates the process. Requests wait for the first
 * emission instead of being told the provider is off, and fall back to
 * disabled if it never arrives.
 */
internal object AndroidIpcProviderGate {
    /**
     * How long a request waits for the first preference emission. Only a cold
     * start that begins with an incoming bind can reach this; the collector
     * normally publishes within milliseconds of `Application.onCreate`.
     */
    private val RESOLVE_TIMEOUT = 5.seconds

    // null = not resolved yet. Distinguishing "unknown" from "disabled" is what
    // lets a request wait rather than be told the provider is off.
    private val sshEnabled = MutableStateFlow<Boolean?>(null)
    private val openPgpEnabled = MutableStateFlow<Boolean?>(null)

    suspend fun isSshEnabled(): Boolean = sshEnabled.awaitResolved()

    suspend fun isOpenPgpEnabled(): Boolean = openPgpEnabled.awaitResolved()

    fun update(
        sshEnabled: Boolean,
        openPgpEnabled: Boolean,
    ) {
        this.sshEnabled.value = sshEnabled
        this.openPgpEnabled.value = openPgpEnabled
    }

    /** Resolves to disabled when the preference never arrives: fail closed. */
    private suspend fun StateFlow<Boolean?>.awaitResolved(): Boolean =
        withTimeoutOrNull(RESOLVE_TIMEOUT) {
            filterNotNull().first()
        } ?: false
}
