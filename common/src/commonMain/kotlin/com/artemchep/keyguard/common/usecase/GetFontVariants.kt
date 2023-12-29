package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.AppFont
import kotlinx.coroutines.flow.Flow

interface GetFontVariants : () -> Flow<List<AppFont?>>
