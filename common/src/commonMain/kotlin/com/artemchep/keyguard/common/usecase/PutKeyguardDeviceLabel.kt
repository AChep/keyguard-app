package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.P2pGroupId

/**
 * Renames this device in the sync group. The device signs its roster
 * record with the new name, and the other devices show it once they sync.
 */
interface PutKeyguardDeviceLabel : (P2pGroupId, String) -> IO<Unit>
