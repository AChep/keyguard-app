package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KeyguardRevokedPeersWindowTest {
    @Test
    fun `a removal is told within its window, and never one stamped far ahead`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            group.revoke(b)
            val roster = a.roster()
            val at = assertNotNull(RosterActivity(roster, a.crypto::sha256).revokedAt(b.deviceId)).wall

            fun toldAt(nowMillis: Long) = roster
                .revokedPeersToNotify(a.deviceId, a.crypto, nowMillis)
                .map { it.id }

            assertEquals(listOf(b.deviceId), toldAt(at))
            // A clock that is behind the one of the removal by as much as clocks drift.
            assertEquals(listOf(b.deviceId), toldAt(at - HlcClock.MAX_FUTURE_DRIFT_MS))
            // A removal stamped further ahead would keep the room joined for as long.
            assertEquals(emptyList(), toldAt(at - HlcClock.MAX_FUTURE_DRIFT_MS - 1))
            assertEquals(emptyList(), toldAt(at + KeyguardRevokedPeers.TELL_WINDOW_MS))
        }
    }
}
