package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DAppLinkThisDevice
import kotlinx.coroutines.flow.Flow

/**
 * Returns this device as its linked apps know it: its name, and how it reaches them. Both
 * are set before the first link, and stay when the app is unlinked.
 */
interface GetAppLinkThisDevice : () -> Flow<DAppLinkThisDevice>
