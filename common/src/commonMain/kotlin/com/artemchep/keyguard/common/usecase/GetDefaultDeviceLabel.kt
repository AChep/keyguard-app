package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

/**
 * Returns the name this device suggests for itself when it joins a sync
 * group, using a localized fallback when its system name is unavailable.
 */
interface GetDefaultDeviceLabel : () -> IO<String>
