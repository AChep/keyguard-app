package com.artemchep.keyguard.common.service.permission

import kotlinx.coroutines.flow.Flow

interface PermissionService {
    /**
     * Returns a state of target permission; changes if a user
     * allows it in a runtime.
     */
    fun getState(
        permission: Permission,
    ): Flow<PermissionState>
}
