package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.service.vault.FingerprintReadRepository
import com.artemchep.keyguard.common.usecase.GetMasterKdf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class GetMasterKdfImpl(
    private val fingerprintReadRepository: FingerprintReadRepository,
) : GetMasterKdf {
    override fun invoke(): Flow<MasterKdf?> = fingerprintReadRepository
        .get()
        .map { fingerprint -> fingerprint?.master?.kdf }
        .distinctUntilChanged()
}
