package com.artemchep.keyguard.common.service.gpmprivapps

import com.artemchep.keyguard.common.model.DPrivilegedApp
import kotlinx.coroutines.flow.Flow

interface AppPrivilegedAppRepository {
    fun get(): Flow<List<DPrivilegedApp>>
}
