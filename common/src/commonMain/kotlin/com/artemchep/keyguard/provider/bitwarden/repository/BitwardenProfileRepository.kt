package com.artemchep.keyguard.provider.bitwarden.repository

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import kotlinx.coroutines.flow.Flow

interface BitwardenProfileRepository : BaseRepository<BitwardenProfile> {
    fun getById(id: AccountId): Flow<BitwardenProfile?>
}
