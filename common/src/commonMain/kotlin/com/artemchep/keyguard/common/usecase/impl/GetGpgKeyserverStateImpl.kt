package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGpgKeyserverStateImpl(
    private val gpgKeyserverStateRepository: GpgKeyserverStateRepository,
) : GetGpgKeyserverState {
    override fun invoke(
        fingerprint: String,
    ): Flow<DGpgKeyserverState?> = gpgKeyserverStateRepository
        .getByFingerprint(fingerprint)
        .distinctUntilChanged()
}
