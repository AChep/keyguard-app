package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.usecase.GetKeyguardNetworkSettings
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.provider.keyguard.P2pMembershipStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class GetKeyguardNetworkSettingsImpl(
    private val membershipStore: P2pMembershipStore,
) : GetKeyguardNetworkSettings {
    override fun invoke(
        groupId: P2pGroupId,
    ): Flow<P2pNetwork?> = membershipStore
        .observe(groupId)
        .map { membership -> membership?.network }
        .distinctUntilChanged()
}
