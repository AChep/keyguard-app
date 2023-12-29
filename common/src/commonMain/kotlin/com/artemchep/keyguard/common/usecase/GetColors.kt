package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.AppColors
import kotlinx.coroutines.flow.Flow

interface GetColors : () -> Flow<AppColors?>
