package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DKeyguardDevice
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.GetKeyguardDevices
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.provider.keyguard.observeP2pGroup
import kotlinx.coroutines.flow.Flow

class GetKeyguardDevicesImpl(
    private val db: VaultDatabaseManager,
    private val crypto: P2pCrypto,
) : GetKeyguardDevices {
    override fun invoke(
        groupId: P2pGroupId,
    ): Flow<List<DKeyguardDevice>> = db.observeP2pGroup(groupId) { database ->
        database.keyguardDevices(groupId, crypto)
    }
}
