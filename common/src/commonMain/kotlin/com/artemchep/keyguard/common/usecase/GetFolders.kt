package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DFolder
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available on the
 * device accounts.
 */
interface GetFolders : () -> Flow<List<DFolder>>
