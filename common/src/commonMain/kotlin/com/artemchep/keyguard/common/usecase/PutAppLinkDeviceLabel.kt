package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

/**
 * Renames this device for its linked apps. While linked, the device signs its record with
 * the new name, and the other devices show it once they sync.
 */
interface PutAppLinkDeviceLabel : (String) -> IO<Unit>
