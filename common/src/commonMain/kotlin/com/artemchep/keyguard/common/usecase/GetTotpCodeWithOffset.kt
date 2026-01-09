package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import kotlinx.coroutines.flow.Flow

interface GetTotpCodeWithOffset : (TotpToken, Int) -> Flow<TotpCode>
