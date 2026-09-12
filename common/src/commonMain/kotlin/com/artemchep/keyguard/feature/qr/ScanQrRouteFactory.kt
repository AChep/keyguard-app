package com.artemchep.keyguard.feature.qr

import com.artemchep.keyguard.feature.navigation.RouteForResult

/** Bound by apps that include live camera scanning; absent on other platforms. */
interface ScanQrRouteFactory {
    fun create(): RouteForResult<String>
}
