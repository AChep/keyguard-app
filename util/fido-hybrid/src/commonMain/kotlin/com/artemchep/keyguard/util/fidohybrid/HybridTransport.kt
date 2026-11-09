@file:Suppress("MagicNumber") // FIDO/CBOR wire constants and bounded frame sizes.

package com.artemchep.keyguard.util.fidohybrid

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoHybrid
import com.artemchep.keyguard.nativecrypto.NativeHashAlgorithm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

/** Binary WebSocket after the fido.cable upgrade and routing-ID validation. */
interface HybridTunnel {
    val routingId: ByteArray

    /** Binary frames in arrival order; closed, possibly with a cause, when the tunnel ends. */
    val incoming: ReceiveChannel<ByteArray>

    suspend fun send(bytes: ByteArray)
}

/** Only BLE advertising is needed on the phone; the browser scans. */
interface HybridAdvertiser {
    suspend fun start(payload: ByteArray)

    fun stop()
}

/** One QR transaction. All ephemeral key buffers are cleared when it ends. */
class HybridTransport(
    private val aaguid: ByteArray,
    private val relay: HybridRelay = HybridRelay.GOOGLE,
) {
    suspend fun run(
        qr: HybridQr,
        connector: HybridTunnelConnector,
        advertiser: HybridAdvertiser,
        onWaiting: () -> Unit,
        authenticator: HybridAuthenticator,
        guard: () -> Unit = {},
    ): Boolean {
        var succeeded = false
        var eidKeys = byteArrayOf()
        var tunnelId = byteArrayOf()
        try {
            eidKeys = derive(qr.secret, byteArrayOf(), 1, 64)
            tunnelId = derive(qr.secret, byteArrayOf(), 2, 16)
            withTimeout(120_000) {
                val id = tunnelId.toHexString()
                connector.connect(relay, id) { connection ->
                    val tunnel = GuardedHybridTunnel(connection, guard)
                    require(tunnel.routingId.size == 3)
                    val plaintext = ByteArray(16)
                    val nonce = NativeCrypto.primitives.randomBytes(10)
                    nonce.copyInto(plaintext, 1)
                    nonce.fill(0)
                    tunnel.routingId.copyInto(plaintext, 11)
                    plaintext[14] = relay.domainId.toByte()
                    plaintext[15] = (relay.domainId ushr 8).toByte()
                    val psk = derive(qr.secret, plaintext, 3, 32)
                    try {
                        advertise(eidKeys, plaintext, advertiser)
                        onWaiting()
                        val handshake = NativeCryptoHybrid.respond(qr.peerIdentity, psk, tunnel.incoming.receive())
                        try {
                            require(handshake.size == 145)
                            advertiser.stop()
                            tunnel.send(handshake.copyOfRange(0, 81))
                            succeeded = exchange(tunnel, handshake, authenticator)
                        } finally {
                            handshake.fill(0)
                        }
                    } finally {
                        advertiser.stop()
                        psk.fill(0)
                        plaintext.fill(0)
                    }
                }
            }
        } finally {
            eidKeys.fill(0)
            tunnelId.fill(0)
            qr.clear()
        }
        return succeeded
    }

    private suspend fun advertise(
        keys: ByteArray,
        plaintext: ByteArray,
        advertiser: HybridAdvertiser,
    ) {
        val aes = keys.copyOfRange(0, 32)
        val mac = keys.copyOfRange(32, 64)
        try {
            val ciphertext = NativeCrypto.primitives.aesEcbNoPaddingEncrypt(aes, plaintext)
            val tag = NativeCrypto.primitives.hmac(mac, ciphertext, NativeHashAlgorithm.SHA_256)
            try {
                advertiser.start(ciphertext + tag.copyOf(4))
            } finally {
                tag.fill(0)
            }
        } finally {
            aes.fill(0)
            mac.fill(0)
        }
    }

    private suspend fun exchange(
        tunnel: HybridTunnel,
        handshake: ByteArray,
        authenticator: HybridAuthenticator,
    ): Boolean {
        val channel = HybridChannel(tunnel, handshake)
        val session = HybridCtapSession(aaguid)
        try {
            channel.send(HybridCtap.postHandshake(aaguid))
            while (true) {
                val plaintext = channel.receive()
                val outcome =
                    try {
                        answer(channel, session, plaintext, authenticator)
                    } finally {
                        plaintext.fill(0)
                    }
                if (outcome != null) return outcome
            }
        } finally {
            session.clear()
            channel.clear()
        }
    }

    /** Returns the outcome once the response finishes the transaction, or null to await the next request. */
    private suspend fun answer(
        channel: HybridChannel,
        session: HybridCtapSession,
        plaintext: ByteArray,
        authenticator: HybridAuthenticator,
    ): Boolean? {
        when (plaintext[0].toInt()) {
            0 -> throw CancellationException("Browser closed hybrid transaction")
            1 -> require(plaintext.size >= 2)
            else -> error("Unsupported hybrid frame")
        }
        val command = plaintext.copyOfRange(1, plaintext.size)
        val response =
            try {
                channel.untilInterrupted { session.handle(command, authenticator) }
            } finally {
                command.fill(0)
            }
        val success = response.bytes.firstOrNull() == 0.toByte()
        try {
            channel.send(byteArrayOf(1) + response.bytes)
        } finally {
            response.bytes.fill(0)
        }
        return success.takeIf { response.finished }
    }

    private fun derive(
        secret: ByteArray,
        salt: ByteArray,
        purpose: Int,
        length: Int,
    ): ByteArray = NativeCrypto.primitives.hkdfSha256(secret, salt, byteArrayOf(purpose.toByte(), 0, 0, 0), length)
}

private const val UPDATE: Byte = 2
private const val MAX_RECEIVED = 32

/** Post-handshake messages. Counters advance only for frames actually taken from the tunnel. */
private class HybridChannel(
    private val tunnel: HybridTunnel,
    handshake: ByteArray,
) {
    private val receiveKey = handshake.copyOfRange(81, 113)
    private val sendKey = handshake.copyOfRange(113, 145)
    private var receiveCounter = 0
    private var sendCounter = 0

    suspend fun send(plaintext: ByteArray) {
        try {
            tunnel.send(NativeCryptoHybrid.encrypt(sendKey, sendCounter++, plaintext))
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun receive(): ByteArray {
        while (true) {
            open(tunnel.incoming.receive())?.let { return it }
        }
    }

    /**
     * CTAP is request/response: shutdown, another request, or a closed tunnel means the browser gave up.
     * Unlike cancelling a pending read, select never takes a frame it does not process.
     */
    suspend fun <T> untilInterrupted(block: suspend () -> T): T =
        coroutineScope {
            val pending = async { block() }
            do {
                val frame =
                    select<ByteArray?> {
                        pending.onAwait { null }
                        tunnel.incoming.onReceive { it }
                    }
                frame?.let(::open)?.let {
                    it.fill(0)
                    error("Browser interrupted the hybrid request")
                }
            } while (frame != null)
            pending.await()
        }

    /** Update messages are skipped: CTAP 2.2 §11.5.1 allows them both ways but defines no browser keys. */
    private fun open(frame: ByteArray): ByteArray? {
        check(receiveCounter < MAX_RECEIVED) { "Too many hybrid messages" }
        val plaintext = NativeCryptoHybrid.decrypt(receiveKey, receiveCounter++, frame)
        require(plaintext.isNotEmpty())
        if (plaintext[0] != UPDATE) return plaintext
        try {
            HybridCbor.decode(plaintext.copyOfRange(1, plaintext.size))
        } finally {
            plaintext.fill(0)
        }
        return null
    }

    fun clear() {
        receiveKey.fill(0)
        sendKey.fill(0)
    }
}

/** Applies vault/session cancellation to every send, including the handshake. */
private class GuardedHybridTunnel(
    private val delegate: HybridTunnel,
    private val guard: () -> Unit,
) : HybridTunnel by delegate {
    override suspend fun send(bytes: ByteArray) {
        currentCoroutineContext().ensureActive()
        guard()
        delegate.send(bytes)
    }
}
