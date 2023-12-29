package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.MasterSession
import kotlinx.coroutines.flow.Flow

interface GetVaultSession : () -> Flow<MasterSession> {
    /**
     * Returns latest value of the session or `null` if no-one
     * is currently subscribed to the session.
     */
    val valueOrNull: MasterSession?
}
