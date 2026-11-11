package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import kotlinx.coroutines.flow.Flow

/**
 * The client certificates that the operating system keeps for the user.
 *
 * Only bound on platforms that let an app use them, so consumers resolve it
 * with optional injection and hide the option when it is absent.
 */
interface MutualTlsSystemStore {
    /**
     * Emits when collected, and again whenever the app's access to a
     * certificate may have changed, such as after the user chose one:
     * choosing a certificate again grants the access to it again,
     * although its alias stays the same.
     */
    val changes: Flow<Unit>

    /**
     * Shows the certificate chooser of the system with the [current]
     * certificate preselected, and suspends until the user answers.
     * [context] must be anchored to the current UI.
     *
     * Returns `null` if the user picked nothing, which includes a store with
     * no certificates. Throws if the chooser can not be shown.
     */
    suspend fun choose(
        context: LeContext,
        current: ServerEnv.MutualTls.SystemStore?,
    ): ServerEnv.MutualTls.SystemStore?

    /** Returns `null` if the certificate is gone, or the app may no longer use it. */
    suspend fun describe(
        certificate: ServerEnv.MutualTls.SystemStore,
    ): MutualTlsCertificateMetadata?
}
