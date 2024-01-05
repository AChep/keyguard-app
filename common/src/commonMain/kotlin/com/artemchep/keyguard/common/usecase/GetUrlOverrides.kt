package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DGlobalUrlOverride
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available
 * global url overrides.
 */
interface GetUrlOverrides : () -> Flow<List<DGlobalUrlOverride>>
