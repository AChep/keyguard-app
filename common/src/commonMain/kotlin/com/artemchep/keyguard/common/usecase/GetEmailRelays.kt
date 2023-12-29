package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available email
 * relays.
 */
interface GetEmailRelays : () -> Flow<List<DGeneratorEmailRelay>>
