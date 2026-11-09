package com.artemchep.keyguard.feature.p2psync.mailbox

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.DeviceFeature
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestDeviceKeys
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayStorageGateTest {
    private val crypto = TestCrypto()
    private val group = TestGroup(crypto, size = 3)
    private val first get() = group.keys[0]
    private val second get() = group.keys[1]
    private val third get() = group.keys[2]

    /** Returns [roster] with the records of the [devices] signed again with [caps], newer. */
    private fun withCaps(roster: Roster, caps: DeviceCaps, vararg devices: TestDeviceKeys): Roster {
        val updated = devices.map { keys ->
            val record = roster.devices.single { it.id == keys.id }
            group.signer.update(
                device = record,
                updatedAt = Hlc(record.updatedAt.wall + 1_000, 0, keys.id),
                signPrivateKey = keys.signPrivate,
                caps = caps,
            )
        }
        return group.validator.merge(roster, Roster(founder = roster.founder, devices = updated))
    }

    private fun on(roster: Roster, vararg devices: TestDeviceKeys) =
        withCaps(roster, DeviceCaps.current(relayStorage = true), *devices)

    private fun off(roster: Roster, vararg devices: TestDeviceKeys) =
        withCaps(roster, DeviceCaps.current(relayStorage = false), *devices)

    private fun features(roster: Roster): Set<String> = group.activity(roster).caps.features

    /** `true` if the group may use relay storage: every active device lists it. */
    private fun isOpen(roster: Roster): Boolean = DeviceFeature.RELAY_STORAGE in features(roster)

    @Test
    fun `this build lists relay storage only when the user allows it`() {
        assertEquals(emptyList(), DeviceCaps.CURRENT.features)
        assertEquals(DeviceCaps.CURRENT, DeviceCaps.current(relayStorage = false))
        assertEquals(listOf(DeviceFeature.RELAY_STORAGE), DeviceCaps.current(relayStorage = true).features)
        assertEquals(DeviceCaps.CURRENT.kinds, DeviceCaps.current(relayStorage = true).kinds)
    }

    @Test
    fun `the group turns on the features every active device lists`() {
        val roster = group.validator.validate(group.roster)
        assertEquals(emptySet(), features(roster))
        val some = on(roster, first, second)
        assertEquals(emptySet(), features(some))
        val all = on(some, third)
        assertEquals(setOf(DeviceFeature.RELAY_STORAGE), features(all))
        // A feature this build does not know counts the same way.
        val futureCaps = DeviceCaps.current(relayStorage = true)
            .copy(features = listOf("future", DeviceFeature.RELAY_STORAGE))
        val future = withCaps(all, futureCaps, first)
        assertEquals(setOf(DeviceFeature.RELAY_STORAGE), features(future))
    }

    @Test
    fun `the gate opens only when every active device lists relay storage`() {
        val roster = group.validator.validate(group.roster)
        assertFalse(isOpen(roster))
        val some = on(roster, first, third)
        assertFalse(isOpen(some))
        val all = on(some, second)
        assertTrue(isOpen(all))

        // One device that turns it off closes it for the group.
        assertFalse(isOpen(off(all, second)))
    }

    @Test
    fun `a removed device does not hold the gate closed`() {
        val roster = on(group.validator.validate(group.roster), first, second)
        assertFalse(isOpen(roster))
        val removed = group.revoke(roster, third, by = first, at = Hlc(9_000, 0, first.id))
        assertTrue(isOpen(removed))
    }

    @Test
    fun `a state is posted unless the relay holds it, refused it, or a live session acknowledged it`() {
        assertTrue(MailboxPolicy.shouldPost("t1", relayTag = null, refusedTag = null, liveAcked = false))
        assertTrue(MailboxPolicy.shouldPost("t1", relayTag = "t0", refusedTag = "t0", liveAcked = false))
        assertFalse(MailboxPolicy.shouldPost("t1", relayTag = "t1", refusedTag = null, liveAcked = false))
        assertFalse(MailboxPolicy.shouldPost("t1", relayTag = null, refusedTag = "t1", liveAcked = false))
        assertFalse(MailboxPolicy.shouldPost("t1", relayTag = null, refusedTag = null, liveAcked = true))
    }

    @Test
    fun `the missing blobs are bounded, the oldest go first`() {
        var missing = emptyList<String>()
        repeat(MailboxLimits.MAX_MISSING + 2) { missing = MailboxPolicy.withMissing(missing, "h$it") }
        assertEquals(MailboxLimits.MAX_MISSING, missing.size)
        assertEquals("h2", missing.first())
        assertEquals("h${MailboxLimits.MAX_MISSING + 1}", missing.last())
        assertEquals(missing, MailboxPolicy.withMissing(missing, "h5"))
    }

    @Test
    fun `an item is posted again once a quarter of its lifetime is left`() {
        val ttl = 4_000L
        assertFalse(MailboxPolicy.isExpiring(expiresAtMs = null, nowMs = 0L, ttlMs = ttl, aheadPercent = 25L))
        assertFalse(MailboxPolicy.isExpiring(expiresAtMs = 4_000L, nowMs = 2_999L, ttlMs = ttl, aheadPercent = 25L))
        assertTrue(MailboxPolicy.isExpiring(expiresAtMs = 4_000L, nowMs = 3_000L, ttlMs = ttl, aheadPercent = 25L))
        assertTrue(MailboxPolicy.isExpiring(expiresAtMs = 4_000L, nowMs = 5_000L, ttlMs = ttl, aheadPercent = 25L))
    }

    @Test
    fun `a refusal pauses within bounds, and items live at most a week`() {
        assertEquals(MailboxPolicy.DEFAULT_RETRY_AFTER_SECONDS * 1_000L, MailboxPolicy.retryAfterMs(null))
        assertEquals(1_000L, MailboxPolicy.retryAfterMs(0))
        assertEquals(120_000L, MailboxPolicy.retryAfterMs(120))
        assertEquals(MailboxPolicy.MAX_RETRY_AFTER_SECONDS * 1_000L, MailboxPolicy.retryAfterMs(Long.MAX_VALUE))
        assertEquals(MailboxLimits.TTL_SECONDS, MailboxPolicy.ttlSeconds(null))
        assertEquals(3_600L, MailboxPolicy.ttlSeconds(3_600L))
        assertEquals(MailboxLimits.TTL_SECONDS, MailboxPolicy.ttlSeconds(MailboxLimits.TTL_SECONDS * 4))
    }
}
