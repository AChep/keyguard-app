package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.feature.p2psync.session.FrameCodec
import com.artemchep.keyguard.feature.p2psync.session.FrameType
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionStateMachine
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.provider.keyguard.signPrivateKey
import com.artemchep.keyguard.provider.keyguard.transport.GzipCompressor
import com.artemchep.keyguard.provider.keyguard.transport.TransportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Publication waits for the roster's actual writes even when the data hash is unchanged. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyguardPeerPublicationTest {
    @Test
    fun `a roster-only publication waits for its blocked write`() = runTest {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val pair = connect(group)
            val changed = renamed(group)
            assertEquals(pair.initial.contentHash, changed.contentHash)
            val publication = async { pair.sender.publish(changed) }
            pair.stream.blocked.await()
            assertFalse(publication.isCompleted)
            pair.stream.resume.complete(Unit)
            assertTrue(publication.await())
        }
    }

    @Test
    fun `a failed roster write is not successful publication`() = runTest {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val pair = connect(group)
            val publication = async { pair.sender.publish(renamed(group)) }
            pair.stream.blocked.await()
            pair.stream.fail = true
            pair.stream.resume.complete(Unit)
            assertFalse(publication.await())
        }
    }

    @Test
    fun `revocation waits for the roster then writes BYE and finishes`() = runTest {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val pair = connect(group)
            val founder = group.devices.first()
            val peer = group.devices.last()
            val revocation = openTestAuthority(founder.membership.device).use { key ->
                RosterSigner(group.crypto, group.groupId).revoke(
                    group.roster, peer.deviceId, Hlc(KeyguardSessionTestGroup.START_TIME + 10, 0, founder.deviceId),
                    founder.deviceId, key.seed,
                )
            }
            val changed = LocalEnvelope(
                group.roster.copy(revoked = listOf(revocation)),
                Envelope(entries = emptyList()), group.crypto::sha256,
            )
            val publication = async { pair.sender.publish(changed) }
            pair.stream.blocked.await()
            assertFalse(publication.isCompleted)
            pair.stream.resume.complete(Unit)
            assertTrue(publication.await())
            assertEquals(listOf(FrameType.ROSTER, FrameType.BYE), pair.stream.afterBlock.filterNotNull())
        }
    }

    @Test
    fun `a session closed before the edit cannot publish the new roster`() = runTest {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val pair = connect(group)
            pair.sender.close()
            runCurrent()
            assertFalse(pair.sender.publish(renamed(group)))
        }
    }

    @Test
    fun `a roster too large to send never leaves`() = runTest {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val pair = connect(group)
            val founder = group.devices.first()
            // Every record is small, but the authority key signs as many revocations as it likes.
            val at = Hlc(KeyguardSessionTestGroup.START_TIME + 10, 0, founder.deviceId)
            val revocations = openTestAuthority(founder.membership.device).use { key ->
                val signer = RosterSigner(group.crypto, group.groupId)
                List(8_000) { signer.revoke(group.roster, Uuid.random().toString(), at, founder.deviceId, key.seed) }
            }
            val roster = group.roster.copy(revoked = revocations)
            assertTrue(CanonicalJson.encodeToByteArray(Roster.serializer(), roster).size > FrameType.ROSTER.maxSize)
            val changed = LocalEnvelope(roster, Envelope(entries = emptyList()), group.crypto::sha256)
            // The session refuses to adopt it, so no ROSTER message starts.
            pair.sender.publish(changed)
            assertFalse(pair.stream.blocked.isCompleted)
        }
    }

    private fun renamed(group: KeyguardSessionTestGroup): LocalEnvelope {
        val founder = group.devices.first()
        val record = group.roster.devices.single { it.id == founder.deviceId }
        val renamed = RosterSigner(group.crypto, group.groupId).update(
            device = record,
            updatedAt = Hlc(record.updatedAt.wall + 10, 0, record.id),
            signPrivateKey = founder.membership.device.signPrivateKey(),
            label = "New label",
        )
        return LocalEnvelope(
            group.roster.copy(devices = group.roster.devices.map { if (it.id == record.id) renamed else it }),
            Envelope(entries = emptyList()), group.crypto::sha256,
        )
    }

    private data class Pairing(
        val sender: KeyguardPeerSession,
        val stream: BlockedRosterStream,
        val initial: LocalEnvelope,
    )

    private suspend fun TestScope.connect(group: KeyguardSessionTestGroup): Pairing {
        val (a, b) = group.devices
        val (aStream, bStream) = SilentStream.pair()
        val stream = BlockedRosterStream(aStream)
        val initial = LocalEnvelope(group.roster, Envelope(entries = emptyList()), group.crypto::sha256)
        fun session(self: SessionTestDevice, peer: SessionTestDevice, connection: ByteStream) = KeyguardPeerSession(
            peerId = peer.deviceId,
            stream = connection,
            machine = SyncSessionStateMachine(
                SyncSessionConfig(
                    self.deviceId, peer.deviceId, peer.membership.device.noisePublicKeyBase64, group.groupId,
                ),
                group.roster, group.crypto, GzipCompressor,
            ),
            listener = object : KeyguardPeerListener {
                override fun onEnvelope(envelope: Envelope, hash: String) = Unit
                override fun onRoster(roster: Roster) = Unit
            },
            clock = { testScheduler.currentTime },
        )
        val sender = session(a, b, stream)
        val receiver = session(b, a, bStream)
        backgroundScope.launch { sender.run(initial, emptyFlow()) }
        backgroundScope.launch { receiver.run(initial, emptyFlow()) }
        sender.progress.first { it.established && it.upToDate }
        receiver.progress.first { it.established && it.upToDate }
        return Pairing(sender, stream, initial)
    }

    private class BlockedRosterStream(private val inner: ByteStream) : ByteStream by inner {
        val blocked = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val afterBlock = mutableListOf<FrameType?>()
        var fail = false

        override suspend fun write(bytes: ByteArray) {
            val frame = FrameCodec.decode(bytes)
            if (frame.type == FrameType.ROSTER) {
                blocked.complete(Unit)
                resume.await()
                if (fail) throw TransportException("The write failed")
            }
            if (blocked.isCompleted) afterBlock += frame.type
            inner.write(bytes)
        }
    }
}
