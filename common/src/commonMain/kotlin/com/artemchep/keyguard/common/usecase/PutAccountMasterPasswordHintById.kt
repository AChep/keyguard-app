package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId

interface PutAccountMasterPasswordHintById : (
    Map<AccountId, String?>,
) -> IO<Unit>
