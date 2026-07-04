package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.NavItemsConfig
import kotlinx.coroutines.flow.Flow

interface GetPersistedNavItemsConfig : () -> Flow<NavItemsConfig?>
