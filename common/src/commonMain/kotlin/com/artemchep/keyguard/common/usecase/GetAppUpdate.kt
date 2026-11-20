package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DAppUpdate
import kotlinx.coroutines.flow.Flow

/**
 * Emits the newer release the user should know about, or `null`
 * if there is none. Uses the releases the last check found.
 */
interface GetAppUpdate : () -> Flow<DAppUpdate?>
