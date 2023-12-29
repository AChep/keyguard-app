package com.artemchep.keyguard.feature.confirmation.elevatedaccess

sealed interface ElevatedAccessResult {
    data object Deny : ElevatedAccessResult

    data object Allow : ElevatedAccessResult
}
