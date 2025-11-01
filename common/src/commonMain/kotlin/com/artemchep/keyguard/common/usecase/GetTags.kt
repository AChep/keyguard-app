package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DTag
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available on the
 * device tags.
 */
interface GetTags : () -> Flow<List<DTag>>
