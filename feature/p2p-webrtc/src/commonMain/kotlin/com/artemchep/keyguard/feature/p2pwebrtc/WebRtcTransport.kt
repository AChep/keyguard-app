package com.artemchep.keyguard.feature.p2pwebrtc

import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.Signaling
import com.artemchep.keyguard.feature.p2psync.transport.Transport
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.feature.p2psync.transport.TransportPath
import com.artemchep.keyguard.util.webrtc.WebRtcConfig
import com.artemchep.keyguard.util.webrtc.WebRtcException
import com.artemchep.keyguard.util.webrtc.WebRtcFailure
import com.artemchep.keyguard.util.webrtc.WebRtcIceTransportPolicy
import com.artemchep.keyguard.util.webrtc.WebRtcRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** The settings of a [WebRtcTransport]. */
data class WebRtcTransportConfig(
    /** [WebRtcIceTransportPolicy.RELAY] forces TURN, for a "relay only" test mode. */
    val iceTransportPolicy: WebRtcIceTransportPolicy = WebRtcIceTransportPolicy.ALL,
    /** Local UDP addresses to bind, such as `127.0.0.1:0`; empty binds every interface. */
    val bindAddresses: List<String> = emptyList(),
    /**
     * How long [WebRtcTransport.connect] waits for the data channel to open; the answerer
     * first waits as long for an offer.
     */
    val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    /** How often the offerer repeats its offer until the answer arrives. */
    val offerResendMs: Long = OFFER_RESEND_MS,
) {
    companion object {
        /** 20 seconds. */
        const val CONNECT_TIMEOUT_MS: Long = 20_000L

        /** 2 seconds. */
        const val OFFER_RESEND_MS: Long = 2_000L
    }
}

/**
 * Opens WebRTC data channels to the devices of a sync group, signaled through [signaling].
 *
 * Both devices call [connect] for each other. The device with the lexicographically smaller
 * id makes the offer, whatever the [ConnectRole]: it repeats the offer until the answer
 * arrives, and holds its candidates until then, so the other device may start listening
 * late. The answerer creates its peer only once an offer arrives, so a device that waits
 * for a connection that never comes holds no peer. Signals of other attempts, or that are
 * not WebRTC signals, are ignored.
 *
 * Each connection gets a peer of [runtime] with the servers [iceServers] returns, and lives
 * in a child of [scope] until its stream is closed.
 */
class WebRtcTransport(
    private val runtime: WebRtcRuntime,
    private val signaling: Signaling,
    private val localDeviceId: String,
    private val iceServers: suspend () -> List<WebRtcConfig.IceServer>,
    private val scope: CoroutineScope,
    private val config: WebRtcTransportConfig = WebRtcTransportConfig(),
    /** Called with the path of every connection once ICE selects a pair; must not block. */
    private val onSelectedPath: (peerId: String, path: TransportPath) -> Unit = { _, _ -> },
) : Transport {
    companion object {
        /** The label of the one data channel. */
        const val CHANNEL_LABEL: String = "kg"

        private const val SID_BYTES = 8

        /**
         * The signals of the peer that wait to be read. An attempt takes one offer or
         * answer and a few dozen candidates; the oldest go first, and an offer repeats.
         */
        private const val INBOX_CAPACITY = 64

        /** How long a connection waits for its ICE servers before it goes without them. */
        private const val ICE_SERVERS_TIMEOUT_MS = 10_000L
    }

    override val kind: TransportKind = TransportKind.WEBRTC

    /**
     * Connects to the device [peerId] and returns the open data channel.
     *
     * @throws WebRtcTransportException with [WebRtcTransportException.Reason.TIMEOUT] when
     * the channel does not open in time, or [WebRtcTransportException.Reason.CLOSED] when
     * ICE fails first.
     */
    override suspend fun connect(peerId: String, role: ConnectRole): ByteStream {
        require(peerId != localDeviceId) { "A device does not connect to itself" }
        // Kept outside of the scope, which may be cancelled once the channel opened: the
        // stream is closed unless it reaches the caller.
        var opened: ByteStream? = null
        var returned = false
        try {
            return connectWithin(peerId) { opened = it }.also { returned = true }
        } finally {
            val stream = opened
            if (!returned && stream != null) withContext(NonCancellable) { stream.close() }
        }
    }

    private suspend fun connectWithin(peerId: String, onOpened: (ByteStream) -> Unit): ByteStream =
        coroutineScope {
            // Listens before anything is sent, so no reply is missed.
            val inbox = Channel<WebRtcSignal>(INBOX_CAPACITY, BufferOverflow.DROP_OLDEST)
            val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                signaling.incoming.collect { message ->
                    if (message.from == peerId) {
                        WebRtcSignalCodec.decode(message.payload)?.let { inbox.send(it) }
                    }
                }
            }
            try {
                val offer = if (localDeviceId < peerId) {
                    null
                } else {
                    withTimeoutOrNull(config.connectTimeoutMs) { awaitOffer(inbox) }
                        ?: throw WebRtcTransportException(WebRtcTransportException.Reason.TIMEOUT)
                }
                open(peerId, inbox, offer).also(onOpened)
            } finally {
                listener.cancel()
            }
        }

    /** Returns the first offer of the peer; what came before it is of no attempt this one answers. */
    private suspend fun awaitOffer(inbox: ReceiveChannel<WebRtcSignal>): WebRtcSignal {
        var signal = inbox.receive()
        while (signal.kind != WebRtcSignal.Kind.OFFER) signal = inbox.receive()
        return signal
    }

    /** Creates a peer and negotiates its data channel: makes an offer, or answers the [offer]. */
    private suspend fun open(
        peerId: String,
        inbox: ReceiveChannel<WebRtcSignal>,
        offer: WebRtcSignal?,
    ): WebRtcByteStream {
        // A relay that stalls the servers delays no connection: it goes without them.
        val servers = withTimeoutOrNull(ICE_SERVERS_TIMEOUT_MS) { iceServers() }.orEmpty()
        val peer = runtime.createPeer(
            WebRtcConfig(
                iceServers = servers,
                iceTransportPolicy = config.iceTransportPolicy,
                bindAddresses = config.bindAddresses,
            ),
        )
        val connection = WebRtcPeerConnection(peer, scope) { path -> onSelectedPath(peerId, path) }
        var connected = false
        try {
            val stream = withTimeoutOrNull(config.connectTimeoutMs) {
                negotiate(connection, peerId, inbox, offer)
            } ?: throw WebRtcTransportException(WebRtcTransportException.Reason.TIMEOUT)
            connected = true
            return stream
        } finally {
            if (!connected) connection.close()
        }
    }

    /**
     * Negotiates the data channel of [connection] and returns it once it is open: makes an
     * offer, or answers the [offer].
     */
    private suspend fun negotiate(
        connection: WebRtcPeerConnection,
        peerId: String,
        inbox: ReceiveChannel<WebRtcSignal>,
        offer: WebRtcSignal?,
    ): WebRtcByteStream = coroutineScope {
        val sid = offer?.sid ?: Random.nextBytes(SID_BYTES).toHexString()
        val description = if (offer == null) {
            connection.createChannel(CHANNEL_LABEL)
            WebRtcSignal(WebRtcSignal.Kind.OFFER, sid, sdp = connection.createOffer())
        } else {
            WebRtcSignal(WebRtcSignal.Kind.ANSWER, sid, sdp = connection.createAnswer(requireNotNull(offer.sdp)))
        }
        send(peerId, description)
        val resends: Flow<Step> = if (offer == null) {
            flow {
                while (true) {
                    delay(config.offerResendMs)
                    emit(Step.Resend)
                }
            }
        } else {
            emptyFlow()
        }
        val steps = merge(
            inbox.receiveAsFlow().filter { it.sid == sid }.map { Step.Remote(it) },
            connection.localCandidates.map { candidate ->
                Step.Local(WebRtcSignal(WebRtcSignal.Kind.CANDIDATE, sid, candidate = candidate))
            },
            resends,
        )
        val exchange = launch { exchange(connection, peerId, description, steps) }
        connection.awaitOpen()
        exchange.cancel()
        connection.stream
    }

    /**
     * Sends the local candidates and handles the signals of the peer, once the [description]
     * is sent. The offerer repeats its offer and holds its candidates until the answer
     * arrives; the answerer answers again whenever the offer repeats.
     */
    private suspend fun exchange(
        connection: WebRtcPeerConnection,
        peerId: String,
        description: WebRtcSignal,
        steps: Flow<Step>,
    ) {
        val offerer = description.kind == WebRtcSignal.Kind.OFFER
        // Only the offerer waits for an answer.
        var answered = !offerer
        val held = mutableListOf<WebRtcSignal>()

        suspend fun onRemote(signal: WebRtcSignal) {
            when (signal.kind) {
                WebRtcSignal.Kind.ANSWER -> if (!answered) {
                    connection.setRemoteAnswer(requireNotNull(signal.sdp))
                    answered = true
                    held.forEach { send(peerId, it) }
                    held.clear()
                }

                // The answer crossed a repeated offer, or was lost.
                WebRtcSignal.Kind.OFFER -> if (!offerer) send(peerId, description)
                WebRtcSignal.Kind.CANDIDATE -> addRemoteCandidate(connection, requireNotNull(signal.candidate))
            }
        }

        steps.collect { step ->
            when (step) {
                is Step.Remote -> onRemote(step.signal)
                is Step.Local -> if (answered) send(peerId, step.signal) else held += step.signal
                Step.Resend -> if (!answered) send(peerId, description)
            }
        }
    }

    private suspend fun send(peerId: String, signal: WebRtcSignal) {
        signaling.send(peerId, WebRtcSignalCodec.encode(signal))
    }

    /** Adds a candidate of the peer; one the stack can not parse is skipped. */
    private suspend fun addRemoteCandidate(connection: WebRtcPeerConnection, candidate: String) {
        try {
            connection.addIceCandidate(candidate)
        } catch (e: WebRtcException) {
            if (e.failure != WebRtcFailure.INVALID_ARGUMENT && e.failure != WebRtcFailure.INPUT_TOO_LONG) throw e
        }
    }

    private sealed interface Step {
        class Remote(val signal: WebRtcSignal) : Step

        class Local(val signal: WebRtcSignal) : Step

        data object Resend : Step
    }
}
