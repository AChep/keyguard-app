package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.NavItemsConfig
import kotlinx.coroutines.flow.StateFlow

interface GetNavItemsConfig : () -> StateFlow<NavItemsConfig>
