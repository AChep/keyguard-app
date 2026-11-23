package com.artemchep.keyguard.common.service.startup

import com.artemchep.keyguard.common.usecase.ClearDataHook

/**
 * Removes the login item before the erase, since the Flatpak marker that tracks it
 * lives in the app's configuration.
 */
class LaunchAtLoginClearDataHook(
    private val service: LaunchAtLoginService,
) : ClearDataHook {
    override suspend fun beforeClearData() {
        check(service.removeForClearData()) {
            "Failed to remove the launch at login entry, the data is kept so the erase can be retried"
        }
    }
}
