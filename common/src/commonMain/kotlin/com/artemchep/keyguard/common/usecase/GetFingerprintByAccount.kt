package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.AccountId
import kotlinx.coroutines.flow.Flow

interface  GetFingerprintByAccount : (AccountId) -> Flow<String>
