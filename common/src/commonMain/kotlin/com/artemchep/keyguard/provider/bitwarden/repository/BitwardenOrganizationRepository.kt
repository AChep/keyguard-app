package com.artemchep.keyguard.provider.bitwarden.repository

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization

interface BitwardenOrganizationRepository : BaseRepository<BitwardenOrganization> {
    fun getByAccountId(id: AccountId): IO<List<BitwardenOrganization>>
}
