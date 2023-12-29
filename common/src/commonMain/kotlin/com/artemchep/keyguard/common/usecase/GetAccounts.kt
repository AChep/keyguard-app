package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DAccount
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available on the
 * device accounts.
 */
interface GetAccounts : () -> Flow<List<DAccount>>
