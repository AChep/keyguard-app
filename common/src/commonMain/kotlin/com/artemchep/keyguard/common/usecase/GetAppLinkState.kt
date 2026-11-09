package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DAppLinkState
import kotlinx.coroutines.flow.Flow

/** Returns whether this app is linked to the apps on the user's other devices. */
interface GetAppLinkState : () -> Flow<DAppLinkState>
