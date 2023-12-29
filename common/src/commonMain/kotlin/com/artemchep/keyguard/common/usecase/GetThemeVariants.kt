package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.AppTheme
import kotlinx.coroutines.flow.Flow

interface GetThemeVariants : () -> Flow<List<AppTheme?>>
