package com.artemchep.keyguard.provider.keyguard.sync

import com.artemchep.keyguard.core.store.bitwarden.P2pMembership

/**
 * The exchange step of a sync pass: hands the state the pass committed to the other
 * devices of the group, and collects theirs into the [KeyguardInbox]. It never fails for
 * an unreachable relay or peer: devices that are not online now sync later.
 */
fun interface KeyguardSyncExchange {
    /**
     * Exchanges the state of the group [groupId], of which this device has the [membership].
     * Returns `true` if the session ended with the exchange: what the merge after it needs,
     * such as the blobs of new records, waits for another exchange.
     */
    suspend fun exchange(
        groupId: String,
        membership: P2pMembership,
    ): Boolean
}
