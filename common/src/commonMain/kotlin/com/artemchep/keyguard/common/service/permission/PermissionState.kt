package com.artemchep.keyguard.common.service.permission

import com.artemchep.keyguard.platform.LeContext

sealed interface PermissionState {
    data object Granted : PermissionState

    data class Declined(
        val ask: (LeContext) -> Unit,
    ) : PermissionState
}
