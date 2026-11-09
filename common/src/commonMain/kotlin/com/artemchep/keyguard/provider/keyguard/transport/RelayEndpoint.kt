package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.feature.p2psync.compareUnsigned
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2

/**
 * Where a relay is: its base URL as a pairing code or the network settings carry it, an
 * optional separate ICE endpoint, and the optional access token.
 *
 * The base URL is `https://relay.example.com` or `wss://relay.example.com`, with or without
 * the `/v1/ws` path.
 *
 * The token belongs to the relay: the WebSocket and the items the relay keeps get it, see
 * [mailboxUrl], and the ICE endpoint only if it is on the same host, see [iceToken].
 */
class RelayEndpoint(
    relayUrl: String,
    iceUrl: String? = null,
    /** Sent as `Authorization: Bearer <token>`. */
    val token: String? = null,
) {
    /** The WebSocket URL, `ws(s)://…/v1/ws`. */
    val webSocketUrl: String

    /** The ICE servers URL, `http(s)://…/v1/ice-servers` unless given. */
    val iceServersUrl: String

    /** Whether [iceServersUrl] is the relay's own, the only one that sees its tickets. */
    val iceServersAtRelay: Boolean

    /** The base URL of the items the relay keeps, `http(s)://…/v1/mailbox`, see [mailboxUrl]. */
    private val mailboxBaseUrl: String

    /**
     * The [token] if [iceServersUrl] is on the host of the relay, with the same scheme and
     * port; else `null`. A separate ICE endpoint never sees the token of the relay.
     */
    val iceToken: String?

    init {
        val base = relayUrl.trim().removeSuffix("/").removeSuffix(WS_PATH).removeSuffix("/")
        val scheme = schemeOf(base)
        val rest = base.substringAfter("://")
        require(scheme in SCHEMES && rest.isNotEmpty()) { "The relay URL is not an http(s) or ws(s) URL" }
        val secure = scheme == "https" || scheme == "wss"
        // One spelling of one relay, so the saved access token finds it, see
        // [KeyguardRelayDefaults.endpointOfCode]: a lowercase host, no default port.
        val authority = rest.substringBefore('/')
        val defaultPort = if (secure) ":443" else ":80"
        val canonical = authority.lowercase().removeSuffix(defaultPort) + rest.removePrefix(authority)
        webSocketUrl = (if (secure) "wss://" else "ws://") + canonical + WS_PATH
        val relayIceServersUrl = (if (secure) "https://" else "http://") + canonical + ICE_PATH
        iceServersUrl = iceUrl?.trim()?.takeIf(String::isNotEmpty) ?: relayIceServersUrl
        iceServersAtRelay = iceServersUrl == relayIceServersUrl
        mailboxBaseUrl = (if (secure) "https://" else "http://") + canonical + MAILBOX_PATH
        require(token == null || isValidToken(token)) {
            "The relay token has characters other than A-Z a-z 0-9 - . _ ~"
        }
        iceToken = token?.takeIf { originOf(iceServersUrl) == originOf(relayIceServersUrl) }
    }

    /** The URL of the items of [kind] the relay keeps, `http(s)://…/v1/mailbox/{kind}`; they get the [token]. */
    fun mailboxUrl(kind: MailboxKind): String = "$mailboxBaseUrl/${kind.wireName}"

    override fun toString(): String = "RelayEndpoint($webSocketUrl)"

    companion object {
        private const val WS_PATH = "/v1/ws"
        private const val ICE_PATH = "/v1/ice-servers"
        private const val MAILBOX_PATH = "/v1/mailbox"

        private val SCHEMES = setOf("http", "https", "ws", "wss")

        /** `true` if the relay accepts [token] as a bearer token: one or more of A-Z a-z 0-9 - . _ ~. */
        internal fun isValidToken(token: String): Boolean = token.isNotEmpty() && token.all { c ->
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'
        }

        /** The lowercase scheme of [url], what comes before `://`; empty if it has none. */
        internal fun schemeOf(url: String): String = url.substringBefore("://", missingDelimiterValue = "").lowercase()

        /**
         * The host of [url] as written: what follows `://` up to the path, query or fragment,
         * without the user info and the port; `null` if [url] has no `://` or the host is empty.
         * Ktor's `Url` falls back to `localhost` for a missing host, so check this first.
         */
        internal fun hostOrNull(url: String): String? = url
            .substringAfter("://", missingDelimiterValue = "")
            .takeWhile { it != '/' && it != '?' && it != '#' }
            .substringAfterLast('@')
            .substringBefore(':')
            .takeIf(String::isNotEmpty)

        /** The lowercase scheme and authority of [url], such as `https://relay.example.com:8443`. */
        private fun originOf(url: String): String {
            val scheme = schemeOf(url)
            val authority = url.substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .lowercase()
            return "$scheme://$authority"
        }
    }
}

/**
 * A relay room and the key that seals the signaling in it. The room id and the key both
 * derive from a secret the relay never sees, so the relay learns neither the group nor
 * the devices in it.
 */
class RelayRoom private constructor(
    /** The 64-hex room id. */
    val id: String,
    /** The 64-byte AES-CBC-256 + HMAC-SHA256 key of [RelaySignaling]. */
    internal val signalingKey: SymmetricCryptoKey2,
    /**
     * The keys of the items the two devices of a pair leave on the relay for each other,
     * from the same secret as the room; `null` for the room of an enrollment, and for a
     * pair without usable ML-KEM keys, see [PairKemKeys.ofOrNull].
     */
    val mailbox: PairMailbox? = null,
) {
    companion object {
        /** `HMAC-SHA256(psk, "keyguard-invite-room")` names the room of one pairing code. */
        const val INVITE_ROOM_LABEL: String = "keyguard-invite-room"

        /** The HKDF-SHA256 info of the signaling key. */
        const val SIGNALING_KEY_INFO: String = "keyguard/p2p/signaling/v1"

        private const val SIGNALING_KEY_SIZE = 64

        /** The HKDF-SHA256 info prefix of the secret of a pair room. */
        const val PAIR_ROOM_INFO: String = "keyguard/p2p/pair-room/v1"

        private const val PAIR_SECRET_SIZE = 32

        /**
         * The room where two devices of the sync group of the [scope] meet. Its secret is
         * `HKDF-SHA256(ikm = sharedSecret, salt = groupKey, info = PAIR_ROOM_INFO ||
         * scope.roomLabel || min(noisePubA, noisePubB) || max(noisePubA, noisePubB))`, where
         * [sharedSecret] is the X25519 of the Noise key of one device and the public Noise key
         * of the other. Only the two devices compute it: a device the group removed knows
         * the group key, but finds no room of the others. The keys of the items the two
         * devices leave on the relay for each other derive from it and the ML-KEM-1024 keys
         * of the pair, [kem], see [PairMailbox]; without them the pair gets no mailbox.
         */
        // One argument per input of the derivation.
        @Suppress("LongParameterList")
        fun forPair(
            sharedSecret: ByteArray,
            groupKey: ByteArray,
            localNoisePub: ByteArray,
            peerNoisePub: ByteArray,
            kem: PairKemKeys?,
            scope: SyncScope,
            cryptoGenerator: CryptoGenerator,
        ): RelayRoom {
            require(sharedSecret.size == PAIR_SECRET_SIZE) { "The shared secret is not $PAIR_SECRET_SIZE bytes" }
            val first = if (localNoisePub.compareUnsigned(peerNoisePub) <= 0) localNoisePub else peerNoisePub
            val second = if (first === localNoisePub) peerNoisePub else localNoisePub
            val info = (PAIR_ROOM_INFO + scope.roomLabel).encodeToByteArray() + first + second
            return cryptoGenerator
                .hkdf(
                    seed = sharedSecret,
                    salt = groupKey,
                    info = info,
                    length = PAIR_SECRET_SIZE,
                )
                .useAndClear { secret ->
                    val mailbox = kem?.let {
                        PairMailbox.derive(secret, localNoisePub, peerNoisePub, it, cryptoGenerator)
                    }
                    create(secret, scope.roomLabel, cryptoGenerator, mailbox)
                }
        }

        /** The room where the two devices of an enrollment meet, from the code's [psk]. */
        fun forInvite(psk: ByteArray, cryptoGenerator: CryptoGenerator): RelayRoom =
            create(psk, INVITE_ROOM_LABEL, cryptoGenerator)

        private fun create(
            secret: ByteArray,
            label: String,
            cryptoGenerator: CryptoGenerator,
            mailbox: PairMailbox? = null,
        ): RelayRoom {
            require(secret.isNotEmpty()) { "The room secret is empty" }
            val id = cryptoGenerator.hmacSha256(key = secret, data = label.encodeToByteArray())
            val key = cryptoGenerator.hkdf(
                seed = secret,
                info = SIGNALING_KEY_INFO.encodeToByteArray(),
                length = SIGNALING_KEY_SIZE,
            )
            return RelayRoom(
                id = P2pEncoding.hex(id),
                signalingKey = SymmetricCryptoKey2(key),
                mailbox = mailbox,
            )
        }
    }

    override fun toString(): String = "RelayRoom"
}
