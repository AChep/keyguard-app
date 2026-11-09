package com.artemchep.keyguard.feature.passkeys

import com.artemchep.keyguard.feature.navigation.Route

/** Bound by apps that handle cross-device passkey QR codes in the app; absent on other platforms. */
interface PasskeyHybridRouteFactory {
    fun create(): Route
}
