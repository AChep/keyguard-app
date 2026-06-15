package com.artemchep.keyguard.common.usecase

import arrow.core.Either
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import kotlinx.coroutines.flow.Flow

interface GetTotpCode : (TotpToken) -> Flow<Either<Throwable, TotpCode>>
