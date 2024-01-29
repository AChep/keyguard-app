package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.AppVersionLog
import kotlinx.coroutines.flow.Flow

interface GetVersionLog : () -> Flow<List<AppVersionLog>>
