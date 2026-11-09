package com.artemchep.keyguard.feature.passkeyhybrid.presentation

import com.artemchep.keyguard.util.fidohybrid.HybridAuthenticator
import com.artemchep.keyguard.util.fidohybrid.HybridQr

fun interface HybridSessionTransport {
    /** The transport must invoke guard before every send and finish delivery before returning success. */
    suspend fun run(
        qr: HybridQr,
        guard: () -> Unit,
        onWaiting: () -> Unit,
        authenticator: HybridAuthenticator,
    ): Boolean
}
