package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available on the
 * device accounts.
 */
interface GetCiphers : () -> Flow<List<DSecret>>
