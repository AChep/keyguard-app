package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.permission.PermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class PermissionServiceJvm : PermissionService {

    override fun getState(
        permission: Permission,
    ): Flow<PermissionState> {
        val result = PermissionState.Granted
        return MutableStateFlow(result)
    }
}
