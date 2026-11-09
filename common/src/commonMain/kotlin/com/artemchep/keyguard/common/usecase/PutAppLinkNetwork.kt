package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork

/**
 * Changes how this device reaches its linked apps. While linked, the live sync session
 * restarts with the new settings.
 */
interface PutAppLinkNetwork : (P2pNetwork) -> IO<Unit>
