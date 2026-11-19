package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

/** Whether the user turned the browser extension integration on. */
interface GetBrowserExtensionEnabled : () -> Flow<Boolean>
