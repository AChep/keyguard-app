package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork

/**
 * Changes how this device reaches the other devices of its sync group.
 * The live sync session restarts with the new settings.
 */
interface PutKeyguardNetworkSettings : (P2pGroupId, P2pNetwork) -> IO<Unit>
