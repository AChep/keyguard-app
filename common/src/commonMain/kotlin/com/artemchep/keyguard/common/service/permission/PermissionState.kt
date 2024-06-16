package com.artemchep.keyguard.common.service.permission

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.platform.LeContext

@Immutable
sealed interface PermissionState {
    @Immutable
    data object Granted : PermissionState

    @Immutable
    data class Declined(
        val ask: (LeContext) -> Unit,
    ) : PermissionState
}
