package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DAccountStatus
import kotlinx.coroutines.flow.Flow

interface GetAccountStatus : () -> Flow<DAccountStatus>
