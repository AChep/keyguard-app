package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.model.DKeyguardDevice
import kotlinx.coroutines.flow.Flow

/**
 * Returns the devices in the sync group, removed ones
 * included; an empty list if there is no such group.
 */
interface GetKeyguardDevices : (P2pGroupId) -> Flow<List<DKeyguardDevice>>
