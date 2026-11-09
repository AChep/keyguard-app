package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DKeyguardSyncStatus
import com.artemchep.keyguard.common.model.P2pGroupId
import kotlinx.coroutines.flow.Flow

/**
 * Returns the status of the live sync session of the sync group, and what
 * this device knows about the other devices of the group.
 */
interface GetKeyguardSyncStatus : (P2pGroupId) -> Flow<DKeyguardSyncStatus>
