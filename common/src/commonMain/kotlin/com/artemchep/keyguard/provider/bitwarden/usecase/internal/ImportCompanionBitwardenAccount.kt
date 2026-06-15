package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.auth.companion.CompanionBitwardenPayload

interface ImportCompanionBitwardenAccount : (
    CompanionBitwardenPayload,
) -> IO<AccountId>
