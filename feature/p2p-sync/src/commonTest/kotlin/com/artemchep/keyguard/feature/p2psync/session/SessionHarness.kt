package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.testing.IdentityCompressor
import com.artemchep.keyguard.feature.p2psync.testing.InMemoryByteStream
import com.artemchep.keyguard.feature.p2psync.testing.SimDevice
import com.artemchep.keyguard.feature.p2psync.testing.StreamKnobs
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A device that runs sync sessions with its peers. */
class SessionNode(
    val device: SimDevice,
    val now: () -> Long,
) {
    val links = mutableListOf<SessionLink>()
    private val validator = device.group.validator

    /** Hands the current envelope to every open session, as the app does after a change. */
    suspend fun broadcast() {
        links.toList().forEach { link -> link.handle(link.machine.onLocalEnvelopeChanged(device.localEnvelope())) }
    }

    suspend fun applyRoster(effect: SessionEffect.ApplyRoster) {
        val merged = validator.merge(device.roster, effect.roster)
        if (merged != device.roster) {
            device.roster = merged
            broadcast()
        }
    }
}

/** One side of a session: the state machine of [node] talking to [peerId] over [stream]. */
class SessionLink(
    val node: SessionNode,
    val peerId: String,
    private val stream: ByteStream,
    maxEnvelopeSize: Int = SyncSessionConfig.MAX_ENVELOPE_SIZE,
) {
    private val peerRecord = node.device.roster.devices.first { it.id == peerId }

    val machine = SyncSessionStateMachine(
        config = SyncSessionConfig(
            localDeviceId = node.device.id,
            peerDeviceId = peerId,
            peerNoisePub = peerRecord.noisePub,
            groupId = node.device.group.groupId,
            maxEnvelopeSize = maxEnvelopeSize,
        ),
        roster = node.device.roster,
        crypto = node.device.group.crypto,
        compressor = IdentityCompressor,
    )

    /** Every effect the machine returned, in order. */
    val effects = mutableListOf<SessionEffect>()

    val closeReason: CloseReason? get() = effects.filterIsInstance<SessionEffect.Close>().firstOrNull()?.reason

    fun sentTypes(): List<FrameType?> = effects.filterIsInstance<SessionEffect.SendFrame>().map { it.frame.type }

    fun launch(scope: CoroutineScope) {
        scope.launch {
            handle(machine.start(node.now(), node.device.localEnvelope()))
            while (true) {
                val bytes = stream.read() ?: break
                handle(machine.onFrame(bytes))
            }
        }
        scope.launch {
            while (isActive && !machine.isClosed) {
                delay(TICK_MS)
                handle(machine.onTick(node.now()))
            }
        }
    }

    suspend fun handle(list: List<SessionEffect>) {
        effects += list
        list.forEach { effect ->
            when (effect) {
                is SessionEffect.SendFrame -> stream.write(effect.bytes)
                // The roster that came with it was applied first.
                is SessionEffect.ApplyEnvelope -> {
                    val result = node.device.receive(effect.envelope, from = peerId)
                    if (result.changed.isNotEmpty()) node.broadcast()
                }
                is SessionEffect.ApplyRoster -> node.applyRoster(effect)
                SessionEffect.RequestResend -> handle(machine.onLocalEnvelopeChanged(node.device.localEnvelope()))
                is SessionEffect.Close -> stream.close()
                else -> Unit
            }
        }
    }

    companion object {
        const val TICK_MS = 1_000L

        /** Connects [a] and [b] with a fresh stream and starts both sides. */
        fun connect(
            scope: CoroutineScope,
            a: SessionNode,
            b: SessionNode,
            aToB: StreamKnobs = StreamKnobs(),
            bToA: StreamKnobs = StreamKnobs(),
            maxEnvelopeSize: Int = SyncSessionConfig.MAX_ENVELOPE_SIZE,
        ): Pair<SessionLink, SessionLink> {
            val (streamA, streamB) = InMemoryByteStream.pair(scope, aToB, bToA)
            val linkA = SessionLink(a, b.device.id, streamA, maxEnvelopeSize)
            val linkB = SessionLink(b, a.device.id, streamB, maxEnvelopeSize)
            a.links += linkA
            b.links += linkB
            linkA.launch(scope)
            linkB.launch(scope)
            return linkA to linkB
        }
    }
}
