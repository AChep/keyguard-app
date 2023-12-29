package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.NavAnimation
import kotlinx.coroutines.flow.Flow

interface GetNavAnimationVariants : () -> Flow<List<NavAnimation>>
