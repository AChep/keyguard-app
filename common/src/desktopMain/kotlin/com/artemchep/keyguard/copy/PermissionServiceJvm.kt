package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.permission.PermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.kodein.di.DirectDI

class PermissionServiceJvm(
) : PermissionService {
    constructor(
        directDI: DirectDI,
    ) : this(
    )

    override fun getState(
        permission: Permission,
    ): Flow<PermissionState> {
        val result = PermissionState.Granted
        return MutableStateFlow(result)
    }
}
