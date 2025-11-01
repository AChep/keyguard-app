package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId

interface AddKeePassAccount : (
    AddKeePassAccountParams,
) -> IO<AccountId>
