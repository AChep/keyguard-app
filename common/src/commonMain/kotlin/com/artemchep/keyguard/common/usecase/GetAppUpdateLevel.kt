package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.AppUpdateLevel
import kotlinx.coroutines.flow.Flow

interface GetAppUpdateLevel : () -> Flow<AppUpdateLevel>
