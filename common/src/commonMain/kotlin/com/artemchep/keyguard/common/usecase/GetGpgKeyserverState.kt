package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DGpgKeyserverState
import kotlinx.coroutines.flow.Flow

/**
 * Observes the keyserver metadata of the key
 * with the given fingerprint.
 */
interface GetGpgKeyserverState : (String) -> Flow<DGpgKeyserverState?>
