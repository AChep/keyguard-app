package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.permission.PermissionState

data class DAccountStatus(
    val error: Error? = null,
    val pending: Pending? = null,
    val pendingPermissions: List<PermissionState.Declined> = emptyList(),
) {
    data class Error(
        val count: Int,
    )

    data class Pending(
        val count: Int,
    )
}
