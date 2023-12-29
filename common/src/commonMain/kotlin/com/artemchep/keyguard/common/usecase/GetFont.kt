package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.AppFont
import kotlinx.coroutines.flow.Flow

interface GetFont : () -> Flow<AppFont?>
