package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSend
import kotlinx.coroutines.flow.Flow

/**
 */
interface GetSends : () -> Flow<List<DSend>>
