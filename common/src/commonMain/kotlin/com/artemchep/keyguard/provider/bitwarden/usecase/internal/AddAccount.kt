package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken

interface AddAccount : (
    String?,
    ServerEnv,
    ServerTwoFactorToken?,
    String?,
    String,
    String,
) -> IO<AccountId>
