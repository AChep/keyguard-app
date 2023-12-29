package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DCollection
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available on the
 * device collections.
 */
interface GetCollections : () -> Flow<List<DCollection>>
