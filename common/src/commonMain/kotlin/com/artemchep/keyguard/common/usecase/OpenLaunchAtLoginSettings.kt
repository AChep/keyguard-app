package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

/**
 * Opens the OS settings for launch at login.
 */
interface OpenLaunchAtLoginSettings : () -> IO<Unit>
