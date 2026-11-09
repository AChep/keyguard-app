package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DKeyguardRelayStatus
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.provider.bitwarden.usecase.LeaveKeyguardGroupImpl
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkWorker
import com.artemchep.keyguard.provider.keyguard.applink.TEST_MASTER_PASSWORD
import com.artemchep.keyguard.provider.keyguard.signPrivateKey
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Membership changes through the foreground worker, including its session teardown. */
@Suppress("TooManyFunctions")
class KeyguardMembershipLifecycleTest {
    @Test
    fun `unlink keeps its publication lease after the foreground worker releases its hold`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            AppLinkWorker(a.app.components.sync, a.registry).launch(a.scope)
            val worker = AppLinkWorker(b.app.components.sync, b.registry).launch(b.scope)
            awaitConnected(a, b)
            val leave = LeaveKeyguardGroupImpl(
                db = b.dbManager,
                crypto = b.crypto,
                authorityKeys = b.app.authorityKeys,
                dissolve = { b.app.service.dissolve() },
                retainLive = { id ->
                    val retained = checkNotNull(b.registry.retainForPublication(id))
                    object : KeyguardSessionPublication by retained {
                        override suspend fun publish(timeoutMs: Long): Boolean {
                            worker.cancelAndJoin()
                            return retained.publish(timeoutMs)
                        }
                    }
                },
            )
            leave(P2pGroupId.AppLink, TEST_MASTER_PASSWORD).bind()
            eventually { a.state() == DAppLinkState.Alone }
            assertEquals(DAppLinkState.NotLinked, b.state())
            eventually { b.registry.get(P2pGroupId.APP_LINK_ID) == null }
        }
    }

    @Test
    fun `unlink without a live session forgets the group locally`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            LeaveKeyguardGroupImpl(
                db = b.dbManager,
                crypto = b.crypto,
                authorityKeys = b.app.authorityKeys,
                dissolve = { b.app.service.dissolve() },
                retainLive = b.registry::retainForPublication,
            )(P2pGroupId.AppLink, TEST_MASTER_PASSWORD).bind()
            assertEquals(DAppLinkState.NotLinked, b.state())
            assertEquals(DAppLinkState.Linked(1), a.state())
            assertTrue(group.relay.clients.isEmpty())
        }
    }

    @Test
    fun `a roster edit is published without a queued data sync`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            AppLinkWorker(a.app.components.sync, a.registry).launch(a.scope)
            AppLinkWorker(b.app.components.sync, b.registry).launch(b.scope)
            awaitConnected(a, b)
            // The first passes write the object key; then the envelopes stay as they are.
            eventually { ObjectKey.ENTRY_KEY in a.app.entries() && a.app.entries() == b.app.entries() }
            val before = a.app.localEnvelope().contentHash
            a.dbManager.mutate("RenameWithoutSync") { database ->
                val store = KeyguardSyncStore(database, P2pGroupId.APP_LINK_ID)
                val roster = store.loadRoster(a.membership.founderId)
                val record = roster.devices.single { it.id == a.deviceId }
                val renamed = RosterSigner(a.crypto, a.membership.groupId).update(
                    record, Hlc(record.updatedAt.wall + 1, 0, record.id),
                    a.membership.device.signPrivateKey(), label = "Changed name",
                )
                store.saveRoster(roster.copy(devices = roster.devices.map { if (it.id == record.id) renamed else it }))
            }.bind()
            eventually { b.roster().devices.single { it.id == a.deviceId }.label == "Changed name" }
            assertEquals(before, a.app.localEnvelope().contentHash)
        }
    }

    @Test
    fun `an isolated member opens no relay room`() = realTime {
        KeyguardSessionTestGroup(size = 1).use { group ->
            val a = group.devices.single()
            AppLinkWorker(a.app.components.sync, a.registry).launch(a.scope)
            awaitStatus(a) { it.relay == DKeyguardRelayStatus.State.OFFLINE }
            assertTrue(group.relay.clients.isEmpty())
            assertEquals(DAppLinkState.Alone, a.state())
        }
    }

    @Test
    fun `expired revocations open no notification room after restarting`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            val at = Clock.System.now().toEpochMilliseconds() - KeyguardRevokedPeers.TELL_WINDOW_MS - 1L
            val revocation = openTestAuthority(a.membership.device).use { key ->
                RosterSigner(a.crypto, group.groupId).revoke(
                    group.roster, b.deviceId, Hlc(at, 0, a.deviceId), a.deviceId, key.seed,
                )
            }
            KeyguardSyncStore(a.db, P2pGroupId.APP_LINK_ID)
                .saveRoster(group.roster.copy(revoked = listOf(revocation)))
            AppLinkWorker(a.app.components.sync, a.registry).launch(a.scope)
            awaitStatus(a) { it.relay == DKeyguardRelayStatus.State.OFFLINE }
            assertTrue(group.relay.clients.isEmpty())
            assertEquals(DAppLinkState.Alone, a.state())
            assertFalse(a.sync())
        }
    }

    @Test
    fun `three device removal reaches removed and remaining peers`() = realTime {
        KeyguardSessionTestGroup(size = 3).use { group ->
            val (a, b, c) = group.devices
            group.devices.forEach { device ->
                AppLinkWorker(device.app.components.sync, device.registry).launch(device.scope)
            }
            awaitConnected(a, b)
            awaitConnected(a, c)
            group.revoke(b)
            val learned = withTimeoutOrNull(8_000L) {
                while (b.state() != DAppLinkState.Removed || c.state() != DAppLinkState.Linked(1)) delay(100L)
                true
            } == true
            assertTrue(learned, "The removed and remaining peers should both learn the revocation")
        }
    }

    @Test
    fun `three device unlink reaches remaining peers with real workers`() = realTime {
        KeyguardSessionTestGroup(size = 3).use { group ->
            val (a, b, c) = group.devices
            group.devices.forEach { device ->
                AppLinkWorker(device.app.components.sync, device.registry).launch(device.scope)
            }
            awaitConnected(a, b)
            awaitConnected(b, c)
            val leave = LeaveKeyguardGroupImpl(
                db = b.dbManager,
                crypto = b.crypto,
                authorityKeys = b.app.authorityKeys,
                dissolve = { b.app.service.dissolve() },
                retainLive = b.registry::retainForPublication,
            )
            leave(P2pGroupId.AppLink, TEST_MASTER_PASSWORD).bind()
            val learned = withTimeoutOrNull(8_000L) {
                while (a.state() != DAppLinkState.Linked(1) || c.state() != DAppLinkState.Linked(1)) delay(100L)
                true
            } == true
            assertTrue(learned, "The remaining peers should learn that the other device left")
        }
    }

    @Test
    fun `last online peer learns removal with real workers`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            AppLinkWorker(a.app.components.sync, a.registry).launch(a.scope)
            AppLinkWorker(b.app.components.sync, b.registry).launch(b.scope)
            awaitConnected(a, b)
            group.revoke(b)
            val learned = withTimeoutOrNull(8_000L) {
                while (b.state() != DAppLinkState.Removed) delay(100L)
                true
            } == true
            assertTrue(learned, "The only peer should learn that it was removed")
        }
    }

    @Test
    fun `last offline initiator learns removal after its peer restarts`() = offlineRemoval(removedDials = true)

    @Test
    fun `last offline responder learns removal after its peer restarts`() = offlineRemoval(removedDials = false)

    private fun offlineRemoval(removedDials: Boolean) = realTime {
        val (low, high) = List(2) { newTestDevice("Device $it") }.sortedBy { it.deviceId }
        val identities = if (removedDials) listOf(high, low) else listOf(low, high)
        KeyguardSessionTestGroup(size = 2, identities = identities).use { group ->
            val (a, b) = group.devices
            val aWorker = AppLinkWorker(a.app.components.sync, a.registry).launch(a.scope)
            val bWorker = AppLinkWorker(b.app.components.sync, b.registry).launch(b.scope)
            awaitConnected(a, b)
            bWorker.cancelAndJoin()
            group.revoke(b)
            aWorker.cancelAndJoin()
            eventually { a.registry.get(P2pGroupId.APP_LINK_ID) == null }
            AppLinkWorker(a.app.components.sync, a.registry).launch(a.scope)
            AppLinkWorker(b.app.components.sync, b.registry).launch(b.scope)
            val learned = withTimeoutOrNull(SESSION_TIMEOUT) {
                while (b.state() != DAppLinkState.Removed) delay(100L)
                true
            } == true
            assertTrue(
                learned,
                "The returning peer should learn removal: " +
                    "A=${a.registry.get(P2pGroupId.APP_LINK_ID)?.status?.value}, " +
                    "B=${b.registry.get(P2pGroupId.APP_LINK_ID)?.status?.value}",
            )
        }
    }

    @Test
    fun `an isolated member notifies its removed peer through a one-shot exchange`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            group.revoke(b)
            AppLinkWorker(b.app.components.sync, b.registry).launch(b.scope)
            a.sync()
            eventually { b.state() == DAppLinkState.Removed }
            eventually { a.registry.get(P2pGroupId.APP_LINK_ID) == null }
        }
    }

    @Test
    fun `unlink notifies connected peer with real workers`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            AppLinkWorker(a.app.components.sync, a.registry).launch(a.scope)
            AppLinkWorker(b.app.components.sync, b.registry).launch(b.scope)
            awaitConnected(a, b)
            val leave = LeaveKeyguardGroupImpl(
                db = b.dbManager,
                crypto = b.crypto,
                authorityKeys = b.app.authorityKeys,
                dissolve = { b.app.service.dissolve() },
                retainLive = b.registry::retainForPublication,
            )
            leave(P2pGroupId.AppLink, TEST_MASTER_PASSWORD).bind()
            val learned = withTimeoutOrNull(8_000L) {
                while (a.state() != DAppLinkState.Alone) delay(100L)
                true
            } == true
            assertTrue(learned, "The connected peer should learn that the other device left")
        }
    }
}
