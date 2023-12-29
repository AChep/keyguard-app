package com.artemchep.keyguard.common.usecase

import arrow.core.Either
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId

interface SyncAll : () -> IO<Map<AccountId, Either<Throwable, Unit>>>
