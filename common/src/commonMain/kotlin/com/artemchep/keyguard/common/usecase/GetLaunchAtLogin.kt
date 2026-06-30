package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

/**
 * Whether the app is registered to launch at login
 */
interface GetLaunchAtLogin : () -> Flow<Boolean>
