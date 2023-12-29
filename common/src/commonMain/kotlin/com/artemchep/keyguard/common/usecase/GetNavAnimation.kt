package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.NavAnimation
import kotlinx.coroutines.flow.StateFlow

interface GetNavAnimation : () -> StateFlow<NavAnimation>
