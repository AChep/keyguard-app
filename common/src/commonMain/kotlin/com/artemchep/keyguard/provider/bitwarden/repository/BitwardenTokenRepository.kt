package com.artemchep.keyguard.provider.bitwarden.repository

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken

interface BitwardenTokenRepository : BaseRepository<BitwardenToken> {
    fun getById(id: AccountId): IO<BitwardenToken?>
}
