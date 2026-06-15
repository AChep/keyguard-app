package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.AllowScreenshots
import kotlinx.coroutines.flow.Flow

interface GetAllowScreenshots : () -> Flow<AllowScreenshots>
