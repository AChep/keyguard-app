package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import kotlinx.coroutines.flow.Flow

/**
 * Returns how this device reaches the other devices of its sync group,
 * or `null` if there is no such group.
 */
interface GetKeyguardNetworkSettings : (P2pGroupId) -> Flow<P2pNetwork?>
