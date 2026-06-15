package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.auth.companion.CompanionKeePassPayload

object ImportCompanionKeePassAccount {
    data class Params(
        val payload: CompanionKeePassPayload,
        val databaseUri: String,
        val keyUri: String?,
    )
}

interface ImportCompanionKeePassAccountUseCase : (
    ImportCompanionKeePassAccount.Params,
) -> IO<AccountId>
