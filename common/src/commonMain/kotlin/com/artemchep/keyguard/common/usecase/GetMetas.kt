package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DMeta
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available on the
 * device metadata.
 */
interface GetMetas : () -> Flow<List<DMeta>>
