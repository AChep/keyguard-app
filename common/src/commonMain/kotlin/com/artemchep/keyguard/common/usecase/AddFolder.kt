package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId

interface AddFolder : (
    Map<AccountId, String>,
) -> IO<Set<String>>
