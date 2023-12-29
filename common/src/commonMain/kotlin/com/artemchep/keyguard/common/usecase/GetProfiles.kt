package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DProfile
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available on the
 * device profiles.
 */
interface GetProfiles : () -> Flow<List<DProfile>>
