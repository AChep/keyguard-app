package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.provider.bitwarden.usecase.LeaveKeyguardGroupImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutKeyguardDeviceLabelImpl
import com.artemchep.keyguard.provider.keyguard.P2pMembershipStore
import com.artemchep.keyguard.provider.keyguard.applink.TEST_MASTER_PASSWORD
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Roster changes a device makes about itself reach the devices its live session is
 * connected to: a new name, and leaving the link.
 */
class KeyguardSessionRosterTest {
    @Test
    fun `a device renames itself and the other device shows the new name`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            a.startLive()
            b.startLive()
            awaitConnected(a, b)

            val putLabel = PutKeyguardDeviceLabelImpl(
                db = b.dbManager,
                membershipStore = P2pMembershipStore(b.dbManager),
                crypto = b.crypto,
                queueSync = b.app.components.hosts,
                windowCoroutineScope = b.windowScope,
            )
            putLabel(P2pGroupId.AppLink, "Work phone").bind()

            val self = b.deviceId
            eventually { a.roster().devices.singleOrNull { it.id == self }?.label == "Work phone" }
            val record = b.roster().devices.single { it.id == self }
            assertEquals(record, a.roster().devices.single { it.id == self })
        }
    }

    /**
     * A third member stays offline; the connected device still learns the departure.
     */
    @Test
    fun `a device that leaves is removed on the connected device`() = realTime {
        KeyguardSessionTestGroup(size = 3).use { group ->
            val (a, b) = group.devices
            a.startLive()
            b.startLive()
            awaitConnected(a, b)

            val dissolved = mutableListOf<P2pGroupId>()
            val leave = LeaveKeyguardGroupImpl(
                db = b.dbManager,
                crypto = b.crypto,
                authorityKeys = testAuthorityKeys,
                dissolve = { groupId ->
                    dissolved += groupId
                    b.app.service.dissolve()
                },
                retainLive = b.registry::retainForPublication,
            )
            leave(P2pGroupId.AppLink, TEST_MASTER_PASSWORD).bind()
            assertEquals(listOf(P2pGroupId.AppLink), dissolved)
            assertEquals(DAppLinkState.NotLinked, b.state())

            val self = b.deviceId
            eventually { !RosterActivity(a.roster(), a.crypto::sha256).isActive(self) }
            awaitStatus(a) { self !in it.peers }
        }
    }
}
