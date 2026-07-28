package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

/**
 * Registers / unregisters the app to launch at login
 */
interface PutLaunchAtLogin : (Boolean) -> IO<Unit>
