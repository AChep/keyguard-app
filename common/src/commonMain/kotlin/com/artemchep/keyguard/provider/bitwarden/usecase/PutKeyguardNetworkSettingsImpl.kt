package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.usecase.PutKeyguardNetworkSettings
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.core.store.bitwarden.normalized
import com.artemchep.keyguard.provider.keyguard.P2pMembershipStore
import kotlinx.coroutines.Dispatchers

/**
 * Saves the settings in the sync group membership. The session runs with the
 * membership, so changed settings start it over, see [com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionRegistry].
 */
class PutKeyguardNetworkSettingsImpl(
    private val membershipStore: P2pMembershipStore,
) : PutKeyguardNetworkSettings {
    override fun invoke(
        groupId: P2pGroupId,
        network: P2pNetwork,
    ): IO<Unit> = ioEffect(Dispatchers.Default) {
        val normalized = network.normalized()
        membershipStore.update(groupId) { membership ->
            membership.copy(network = normalized)
        }
        Unit
    }
}
