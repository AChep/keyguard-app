package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.service.startup.LaunchAtLoginState
import kotlinx.coroutines.flow.Flow

/**
 * Whether the app is registered to launch at login. Emits the last known state at once
 * and re-reads the OS registration in the background.
 */
interface GetLaunchAtLogin : () -> Flow<LaunchAtLoginState>
