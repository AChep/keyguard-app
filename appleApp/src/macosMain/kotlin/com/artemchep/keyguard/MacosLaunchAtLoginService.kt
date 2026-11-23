package com.artemchep.keyguard

import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginRegistration
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginService
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginStatus
import com.artemchep.keyguard.common.service.startup.impl.LaunchAtLoginServiceImpl
import com.artemchep.keyguard.util.launchatlogin.LaunchAtLogin
import com.artemchep.keyguard.util.launchatlogin.LaunchAtLoginTarget
import com.artemchep.keyguard.util.launchatlogin.LaunchAtLoginStatus as NativeLaunchAtLoginStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal class MacosLaunchAtLoginService(
    readStatus: () -> NativeLaunchAtLoginStatus,
    writeEnabled: (Boolean) -> NativeLaunchAtLoginStatus,
    showSettings: () -> Unit,
    logRepository: LogRepository,
    dispatcher: CoroutineDispatcher,
) : LaunchAtLoginService by LaunchAtLoginServiceImpl(
    createRegistration = { MacosLaunchAtLoginRegistration(readStatus, writeEnabled, showSettings) },
    logRepository = logRepository,
    dispatcher = dispatcher,
) {
    constructor(logRepository: LogRepository) : this(LaunchAtLogin(LaunchAtLoginTarget.MacOSMainApp), logRepository)

    private constructor(client: LaunchAtLogin, logRepository: LogRepository) : this(
        readStatus = client::status,
        writeEnabled = client::setEnabled,
        showSettings = client::openSettings,
        logRepository = logRepository,
        dispatcher = Dispatchers.IO,
    )
}

private class MacosLaunchAtLoginRegistration(
    private val readStatus: () -> NativeLaunchAtLoginStatus,
    private val writeEnabled: (Boolean) -> NativeLaunchAtLoginStatus,
    private val showSettings: () -> Unit,
) : LaunchAtLoginRegistration {
    override suspend fun status() = readStatus().toLaunchAtLoginStatus()

    override suspend fun setEnabled(enabled: Boolean) = writeEnabled(enabled).toLaunchAtLoginStatus()

    override suspend fun openSettings() = showSettings()
}

private fun NativeLaunchAtLoginStatus.toLaunchAtLoginStatus() = LaunchAtLoginStatus.valueOf(name)
