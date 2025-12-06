package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available
 * global url blocks.
 */
interface GetUrlBlocks : () -> Flow<List<DGlobalUrlBlock>>
