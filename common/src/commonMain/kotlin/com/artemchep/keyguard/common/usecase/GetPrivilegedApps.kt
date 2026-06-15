package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DPrivilegedApp
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available
 * privileged apps.
 */
interface GetPrivilegedApps : () -> Flow<List<DPrivilegedApp>>
