package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.TransportPath
import com.artemchep.keyguard.feature.p2psync.transport.TransportSessionContext
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.provider.keyguard.transport.GroupObjects
import com.artemchep.keyguard.provider.keyguard.transport.IceServersGrant
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardIceServers
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayConnector
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardTransportContext
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardTransportFactory
import com.artemchep.keyguard.provider.keyguard.transport.PairKemKeys
import com.artemchep.keyguard.provider.keyguard.transport.RelayClient
import com.artemchep.keyguard.provider.keyguard.transport.RelayEndpoint
import com.artemchep.keyguard.provider.keyguard.transport.RelayForwardTransport
import com.artemchep.keyguard.provider.keyguard.transport.RelayRoom
import com.artemchep.keyguard.provider.keyguard.transport.RelayState
import com.artemchep.keyguard.provider.keyguard.transport.RelaySignaling
import com.artemchep.keyguard.provider.keyguard.transport.SelectedStream
import com.artemchep.keyguard.provider.keyguard.transport.TransportSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Which relay room to join, and as whom.
 *
 * @property localDeviceId the id this device announces in the room.
 * @property allowLocalNetwork `false` keeps direct connections off the local network.
 */
class KeyguardRoomSpec(
    val endpoint: RelayEndpoint,
    val room: RelayRoom,
    val localDeviceId: String,
    val allowLocalNetwork: Boolean,
)

/**
 * A joined relay room: the [relay] connection, the [signaling] that names the devices in
 * it, and the [selector] of the transports to them.
 */
class KeyguardRoom(
    val relay: RelayClient,
    val signaling: RelaySignaling,
    val selector: TransportSelector,
) {
    /**
     * Connects to the device [peerId] of the room over the transports both devices support,
     * see [TransportSelector.connect]. The stream names the ICE servers its connection was
     * given, if any: a connection relayed through TURN lasts only as long as their
     * credentials.
     */
    suspend fun connect(peerId: String, role: ConnectRole): SelectedStream {
        val given = IceServersGiven()
        val selected = withContext(given) { selector.connect(peerId, role, signaling.transportsOf(peerId)) }
        return SelectedStream(selected.kind, selected.stream, iceServers = given.grant)
    }
}

/**
 * Records the ICE servers a transport takes for a connection opened under it, see
 * [KeyguardRoom.connect]. A race tries one WebRTC connection at a time, so the last one
 * given is the one of the connection.
 */
internal class IceServersGiven : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<IceServersGiven>

    @Volatile
    var grant: IceServersGrant? = null
}

/**
 * Joins relay rooms for sync sessions and enrollments. While a room is joined, the relay
 * client, its signaling and relay forwarding all run, and the transports of
 * [transportFactory] serve the peers.
 */
class KeyguardRoomConnector(
    private val relayConnector: KeyguardRelayConnector,
    private val iceServers: KeyguardIceServers,
    private val transportFactory: KeyguardTransportFactory,
    private val cryptoGenerator: CryptoGenerator,
    private val cipherEncryptor: CipherEncryptor,
) {
    private val randomBytes: (Int) -> ByteArray = { length -> cryptoGenerator.seed(length = length) }

    /**
     * The room where this device, with the Noise key pair [localNoisePrivate] and
     * [localNoisePub], meets the device with the Noise key [peerNoisePub] in the sync group
     * of the [scope] with the 32-byte [groupKey], see [RelayRoom.forPair]. The room has a
     * mailbox once the ML-KEM-1024 keys of the pair, the [localKemPrivate] key of this device
     * and the [peerKemPub] key of the peer, are usable, see [PairKemKeys.ofOrNull].
     *
     * @throws com.artemchep.keyguard.nativecrypto.NativeCryptoException for a malformed
     *   or low-order key.
     */
    // One argument per input of the derivation.
    @Suppress("LongParameterList")
    fun pairRoom(
        groupKey: ByteArray,
        scope: SyncScope,
        localNoisePrivate: ByteArray,
        localNoisePub: ByteArray,
        peerNoisePub: ByteArray,
        localKemPrivate: ByteArray,
        peerKemPub: ByteArray?,
    ): RelayRoom {
        val kem = peerKemPub?.let { PairKemKeys.ofOrNull(localKemPrivate, it) }
        return NativeCryptoPrimitives.x25519(localNoisePrivate, peerNoisePub).useAndClear { shared ->
            RelayRoom.forPair(shared, groupKey, localNoisePub, peerNoisePub, kem, scope, cryptoGenerator)
        }
    }

    /** The room of the enrollment with the pre-shared key [psk]. */
    fun inviteRoom(psk: ByteArray): RelayRoom = RelayRoom.forInvite(psk, cryptoGenerator)

    /**
     * The keys of the objects the devices of the sync group of the [scope] with the
     * [objectKey] and the 32-byte [groupKey] share on the relay, see [GroupObjects].
     */
    fun groupObjects(objectKey: ObjectKey, groupKey: ByteArray, scope: SyncScope): GroupObjects =
        GroupObjects.derive(objectKey, groupKey, scope.roomLabel, cryptoGenerator)

    /**
     * Joins the room of [spec] and runs [block] in it. Leaving [block] leaves the room and
     * closes every transport the room opened.
     *
     * @param onSelectedPath called with the path a direct connection to a peer settled on.
     *   It must not block.
     */
    suspend fun <T> join(
        spec: KeyguardRoomSpec,
        onSelectedPath: (peerId: String, path: TransportPath) -> Unit = { _, _ -> },
        block: suspend CoroutineScope.(KeyguardRoom) -> T,
    ): T = coroutineScope {
        val connection = relayConnector.open(spec.endpoint, spec.room)
        // The hello of this device lists them, once the room runs.
        lateinit var selector: TransportSelector
        val signaling = RelaySignaling(
            client = connection.client,
            room = spec.room,
            localDeviceId = spec.localDeviceId,
            cipherEncryptor = cipherEncryptor,
            randomBytes = randomBytes,
            transports = { selector.kinds },
        )
        val forward = RelayForwardTransport(
            client = connection.client,
            resolveRelayPeer = signaling::relayPeerOf,
            randomBytes = randomBytes,
        )
        val roomJob = Job(coroutineContext.job)
        val roomScope = CoroutineScope(coroutineContext + roomJob)
        try {
            val provider = iceServers.get(spec.endpoint)
            val context = KeyguardTransportContext(
                relayForward = forward,
                session = TransportSessionContext(
                    localDeviceId = spec.localDeviceId,
                    signaling = signaling,
                    iceServers = {
                        val grant = provider.grant(ticket(connection.client))
                        currentCoroutineContext()[IceServersGiven]?.grant = grant
                        grant.servers
                    },
                    scope = roomScope,
                    allowLocalNetwork = spec.allowLocalNetwork,
                    onSelectedPath = onSelectedPath,
                ),
            )
            selector = TransportSelector(transportFactory.create(context))
            // The relay reader stalls unless all three run.
            roomScope.launch { connection.run() }
            roomScope.launch { signaling.run() }
            roomScope.launch { forward.run() }
            val room = KeyguardRoom(
                relay = connection.client,
                signaling = signaling,
                selector = selector,
            )
            block(room)
        } finally {
            // The scope waits for the room to close, WebRTC runtimes included.
            roomJob.cancel()
        }
    }

    /**
     * Returns the ticket of the connection of [client] once it joined: the relay hands out
     * TURN credentials only to a request with a live ticket. Without a join soon, `null`.
     */
    private suspend fun ticket(client: RelayClient): String? = withTimeoutOrNull(TICKET_WAIT_MS) {
        client.state.filterIsInstance<RelayState.Joined>().first()
    }?.ticket

    private companion object {
        const val TICKET_WAIT_MS: Long = 5_000L
    }
}
