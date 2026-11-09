package com.artemchep.keyguard.feature.p2psync.enroll

import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.model.PairingCode
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.session.FrameCodec
import com.artemchep.keyguard.feature.p2psync.session.FrameType
import com.artemchep.keyguard.feature.p2psync.session.Message
import com.artemchep.keyguard.feature.p2psync.session.OutgoingMessages
import com.artemchep.keyguard.feature.p2psync.session.ReassemblyResult
import com.artemchep.keyguard.feature.p2psync.session.Reassembler
import com.artemchep.keyguard.feature.p2psync.session.decodePayloadOrNull
import com.artemchep.keyguard.feature.p2psync.session.encodePayload

/**
 * Adds a device to a sync group. Sans-IO: the driver feeds [EnrollmentEvent]s and carries
 * out the returned [EnrollmentEffect]s in order. One machine runs one enrollment; a new
 * code needs a new machine.
 *
 * 1. The displayer shows a code with a one-time PSK, valid for 3 minutes by its own clock;
 *    the scanner reads it and connects. The code says whether its displayer is the member.
 *    The displayer enforces the expiry; the scanner, whose clock may differ, turns down only
 *    a code expired by more than [EnrollmentTimings.clockSkewMs].
 * 2. The devices run Noise XXpsk3; the scanner checks that the displayer's static key is
 *    the one in the code. The first finished handshake burns the code.
 * 3. The displayer sends ENROLL_HELLO with its versions, role, device record and the
 *    commitment to a nonce; the scanner answers with its ENROLL_HELLO and a nonce of its
 *    own; the displayer reveals its nonce with ENROLL_REVEAL. The devices speak the lower of
 *    their versions, see [version]. Both devices show the same digits, see
 *    [SasDerivation]. The member's ENROLL_HELLO names the group; the joiner announces its
 *    record signed for it in its next message, its ENROLL_HELLO or its ENROLL_REVEAL.
 * 4. The user of each device checks the digits and confirms: the displayer's user types the
 *    digits the scanner shows, and the scanner's user compares them with the ones the
 *    displayer then shows. The joiner sends ENROLL_CONFIRM; once the member's user approved
 *    too, the member sends the bundle, with the admission of the joiner it holds in memory.
 *    Nothing is sent before both confirmed, and the joiner takes no bundle before its user
 *    confirmed.
 * 5. The joiner stores the bundle and acknowledges it with ENROLL_ACK; only then does the
 *    member store the admission, see [EnrollmentEffect.SaveAdmission]. A joiner that ends
 *    the enrollment before then leaves the roster of the member as it was. Both devices
 *    check that the bundle admits the joiner, with its keys, before it leaves the member or
 *    is stored.
 *
 * Every wait is bounded; a failure or a rejection ends the enrollment for good.
 */
// One handler per event and message of the protocol.
@Suppress("TooManyFunctions")
class EnrollmentStateMachine(
    private val config: EnrollmentConfig,
    private val crypto: P2pCrypto,
) {
    private val progress = EnrollmentProgress()
    private val reassembler = Reassembler()
    private var now = 0L
    private var code: PairingCode? = null
    private var handshakeHash: ByteArray? = null
    private var self: RosterDevice? = null
    private var peer: RosterDevice? = null

    /** Returns the joiner's record signed for a group, see [EnrollmentEvent.HandshakeDone.announce]. */
    private var announce: ((group: String) -> RosterDevice)? = null

    /** The nonce of the displayer; the scanner learns it from the reveal. */
    private var displayerNonce: ByteArray? = null

    /** The displayer's commitment to its nonce, which the scanner holds until the reveal. */
    private var commitment: ByteArray? = null

    /** The nonce of the scanner. */
    private var scannerNonce: ByteArray? = null

    /** The bundle the member's user approved, and its bytes, held until the joiner confirms. */
    private var approved: Pair<EnrollmentBundle, ByteArray>? = null

    /** The bundle the member sent, held until the joiner acknowledges it. */
    private var sent: EnrollmentBundle? = null

    /** `true` once the joiner's user confirmed the digits. */
    private var peerConfirmed = false

    private val isDisplayer: Boolean get() = config.display == DisplayMode.DISPLAYER

    /** `true` once the enrollment succeeded or failed. */
    val isFinished: Boolean get() = progress.isFinished

    /**
     * The enrollment protocol version the devices agreed on once the HELLOs met: the lower
     * of their versions, if each speaks it. `null` until then.
     */
    var version: Int? = null
        private set

    /** `true` while the digits are shown and this device's user has not confirmed them yet. */
    val awaitsApproval: Boolean get() = progress.phase == EnrollmentPhase.AWAITING_APPROVAL

    /** Starts at [now]: a displayer creates and shows its code, a scanner waits for one. */
    fun start(now: Long): List<EnrollmentEffect> {
        check(progress.phase == EnrollmentPhase.NEW) { "The enrollment was already started" }
        this.now = now
        return if (isDisplayer) {
            val created = PairingCode(
                psk = crypto.randomBytes(PairingCode.KEY_SIZE),
                pub = checkNotNull(P2pEncoding.base64UrlOrNull(config.device.noisePub)) {
                    "The Noise key of this device is malformed."
                },
                member = config.role == EnrollmentRole.MEMBER,
                // A code holds whole seconds.
                expiresAt = (now + config.timings.inviteTtlMs) / MILLIS_PER_SECOND,
                scope = config.scope,
                relay = config.relay,
                ice = config.ice,
            )
            code = created
            progress.enter(EnrollmentPhase.AWAITING_HANDSHAKE, created.expiresAtMillis)
            listOf(EnrollmentEffect.ShowCode(created))
        } else {
            progress.enter(EnrollmentPhase.AWAITING_CODE, Long.MAX_VALUE)
            emptyList()
        }
    }

    /** Handles one [event]; events before the start and after the end are ignored. */
    fun on(event: EnrollmentEvent): List<EnrollmentEffect> =
        if (progress.isFinished || progress.phase == EnrollmentPhase.NEW) {
            emptyList()
        } else {
            when (event) {
                is EnrollmentEvent.CodeScanned -> onCodeScanned(event)
                is EnrollmentEvent.HandshakeDone -> onHandshakeDone(event)
                is EnrollmentEvent.Approved -> onApproved(event.bundle)
                EnrollmentEvent.Confirmed -> onConfirmed()
                EnrollmentEvent.Rejected -> progress.reject(EnrollmentFailure.REJECTED)
                is EnrollmentEvent.Frame -> onFrame(event.bytes)
                is EnrollmentEvent.Tick -> onTick(event.now)
            }
        }

    private fun onCodeScanned(event: EnrollmentEvent.CodeScanned): List<EnrollmentEffect> {
        now = event.now
        val scanned = event.code
        val expectsMember = config.role == EnrollmentRole.JOINER
        return when {
            progress.phase != EnrollmentPhase.AWAITING_CODE -> emptyList()
            // The displayer's clock set the expiry; it alone enforces it to the millisecond.
            scanned.isExpired(now - config.timings.clockSkewMs) -> progress.fail(EnrollmentFailure.EXPIRED)
            scanned.scope != config.scope -> progress.fail(EnrollmentFailure.SCOPE_MISMATCH)
            scanned.member != expectsMember -> progress.fail(EnrollmentFailure.ROLE_MISMATCH)
            else -> {
                code = scanned
                progress.enter(EnrollmentPhase.AWAITING_HANDSHAKE, now + config.timings.handshakeTimeoutMs)
                listOf(EnrollmentEffect.Connect(scanned))
            }
        }
    }

    private fun onHandshakeDone(event: EnrollmentEvent.HandshakeDone): List<EnrollmentEffect> {
        val current = code
        val pinned = isDisplayer || current?.pub?.contentEquals(event.remoteStatic) == true
        val localStatic = P2pEncoding.base64UrlOrNull(config.device.noisePub)
        if (progress.phase == EnrollmentPhase.AWAITING_HANDSHAKE) progress.connected = true
        return when {
            progress.phase != EnrollmentPhase.AWAITING_HANDSHAKE || current == null -> emptyList()
            !pinned || localStatic == null -> progress.reject(EnrollmentFailure.MISMATCH)
            else -> {
                progress.remoteStatic = event.remoteStatic
                handshakeHash = event.handshakeHash
                self = config.device
                announce = event.announce
                progress.enter(EnrollmentPhase.AWAITING_HELLO, now + config.timings.handshakeTimeoutMs)
                if (isDisplayer) {
                    // The displayer speaks first, committed to a nonce it reveals last.
                    val nonce = crypto.randomBytes(SasDerivation.NONCE_SIZE)
                    displayerNonce = nonce
                    sendHello(commit = SasDerivation.commit(nonce, crypto))
                } else {
                    emptyList()
                }
            }
        }
    }

    private fun sendHello(
        commit: ByteArray? = null,
        nonce: ByteArray? = null,
    ): List<EnrollmentEffect> {
        val hello = EnrollHelloPayload(
            v = config.version,
            min = config.minVersion,
            role = config.role,
            device = checkNotNull(self),
            scope = config.scope,
            group = config.group.takeIf { config.role == EnrollmentRole.MEMBER },
            commit = commit?.let(P2pEncoding::base64Url),
            nonce = nonce?.let(P2pEncoding::base64Url),
        )
        return progress.send(FrameType.ENROLL_HELLO, EnrollHelloPayload.serializer().encodePayload(hello))
    }

    /** Takes a frame of the Noise stream, which is reliable and ordered: one that does not fit ends the enrollment. */
    private fun onFrame(bytes: ByteArray): List<EnrollmentEffect> =
        when (val result = FrameCodec.decodeOrNull(bytes)?.let(reassembler::accept)) {
            null, is ReassemblyResult.Failed -> progress.reject(EnrollmentFailure.PROTOCOL_ERROR)
            ReassemblyResult.Pending -> emptyList()
            is ReassemblyResult.Complete -> onMessage(result.message)
        }

    private fun onMessage(message: Message): List<EnrollmentEffect> {
        val payload = message.payload
        return when (message.type) {
            FrameType.ENROLL_HELLO -> if (progress.phase == EnrollmentPhase.AWAITING_HELLO) {
                onPeerHello(payload)
            } else {
                emptyList()
            }

            FrameType.ENROLL_REVEAL -> onReveal(EnrollRevealPayload.serializer().decodePayloadOrNull(payload))
            FrameType.ENROLL_CONFIRM -> onPeerConfirmed()
            FrameType.ENROLL_BUNDLE -> onBundle(EnrollmentBundleCodec.decodeOrNull(payload))
            FrameType.ENROLL_ACK -> onAck()

            FrameType.ENROLL_REJECT -> {
                val reason = EnrollRejectPayload.serializer().decodePayloadOrNull(payload)?.reason
                val failure = EnrollmentFailure.entries.firstOrNull { it.name == reason }
                progress.fail(failure.seenByReceiver())
            }

            else -> emptyList()
        }
    }

    /**
     * Takes the ENROLL_HELLO of the peer, its [payload]. Its versions come first: the devices
     * speak the lower of their versions, if each speaks it, else the older device needs an
     * update, whatever else the HELLO holds.
     */
    private fun onPeerHello(payload: ByteArray): List<EnrollmentEffect> {
        val versions = EnrollVersionsPayload.serializer().decodePayloadOrNull(payload)
        return when {
            versions == null || versions.min < 1 || versions.min > versions.v ->
                progress.reject(EnrollmentFailure.PROTOCOL_ERROR)

            minOf(config.version, versions.v) < maxOf(config.minVersion, versions.min) -> progress.reject(
                // The older device speaks too old a version for the newer one.
                if (config.version < versions.v) {
                    EnrollmentFailure.UPDATE_REQUIRED
                } else {
                    EnrollmentFailure.PEER_UPDATE_REQUIRED
                },
            )

            else -> {
                version = minOf(config.version, versions.v)
                onPeerHello(EnrollHelloPayload.serializer().decodePayloadOrNull(payload))
            }
        }
    }

    private fun onPeerHello(hello: EnrollHelloPayload?): List<EnrollmentEffect> {
        val keyMatches = hello?.device?.noisePub == progress.remoteStatic?.let(P2pEncoding::base64Url)
        // The displayer commits; the scanner sends its nonce only once it holds the commitment.
        val commit = hello?.commit?.let(P2pEncoding::base64UrlOrNull)
        val nonce = hello?.nonce?.let(P2pEncoding::base64UrlOrNull)
        val expected = if (isDisplayer) {
            commit == null && nonce?.size == SasDerivation.NONCE_SIZE
        } else {
            nonce == null && commit?.size == COMMIT_SIZE
        }
        // The joiner announces its record signed for the group the member names.
        val announced = announced(hello)
        return when {
            hello == null -> progress.reject(EnrollmentFailure.PROTOCOL_ERROR)
            hello.scope != config.scope -> progress.reject(EnrollmentFailure.SCOPE_MISMATCH)
            hello.role == config.role -> progress.reject(EnrollmentFailure.ROLE_MISMATCH)
            !keyMatches -> progress.reject(EnrollmentFailure.MISMATCH)
            !expected || announced == null -> progress.reject(EnrollmentFailure.PROTOCOL_ERROR)
            else -> acceptHello(hello, announced, commit = commit, nonce = nonce)
        }
    }

    /**
     * Takes the valid ENROLL_HELLO of the peer, with the [commit] or the [nonce] it carries,
     * and announces the record [announced] from now on: the displayer reveals its nonce, the
     * scanner answers with its own.
     */
    private fun acceptHello(
        hello: EnrollHelloPayload,
        announced: RosterDevice,
        commit: ByteArray?,
        nonce: ByteArray?,
    ): List<EnrollmentEffect> {
        self = announced
        peer = hello.device
        return if (isDisplayer) {
            scannerNonce = nonce
            val reveal = EnrollRevealPayload(
                nonce = P2pEncoding.base64Url(checkNotNull(displayerNonce)),
                device = announced.takeIf { config.role == EnrollmentRole.JOINER },
            )
            progress.send(FrameType.ENROLL_REVEAL, EnrollRevealPayload.serializer().encodePayload(reveal)) +
                showSas()
        } else {
            commitment = commit
            val own = crypto.randomBytes(SasDerivation.NONCE_SIZE)
            scannerNonce = own
            progress.enter(EnrollmentPhase.AWAITING_REVEAL, now + config.timings.handshakeTimeoutMs)
            sendHello(nonce = own)
        }
    }

    /**
     * The record this device announces once the member's [hello] named its group: the
     * joiner's record signed for it, which must keep the configured id and Noise key, or
     * `null` if it can not. A member, or a joiner without [announce], announces its
     * configured record.
     */
    private fun announced(hello: EnrollHelloPayload?): RosterDevice? {
        val sign = announce
        val group = hello?.group
        return when {
            config.role != EnrollmentRole.JOINER || sign == null || hello?.role != EnrollmentRole.MEMBER -> self
            group == null -> null
            else -> sign(group).takeIf { it.isSameDevice(config.device) }
        }
    }

    private fun onReveal(reveal: EnrollRevealPayload?): List<EnrollmentEffect> {
        val nonce = reveal?.nonce?.let(P2pEncoding::base64UrlOrNull)
        val committed = commitment
        // A displaying joiner announces its record signed for the group only here.
        val joiner = reveal?.device
        val expectsRecord = config.role == EnrollmentRole.MEMBER
        val recordValid = !expectsRecord || joiner != null && peer?.let(joiner::isSameDevice) == true
        return when {
            progress.phase != EnrollmentPhase.AWAITING_REVEAL || committed == null -> emptyList()
            nonce == null || nonce.size != SasDerivation.NONCE_SIZE -> progress.reject(EnrollmentFailure.PROTOCOL_ERROR)
            // A displayer that reveals another nonce than it committed to picked it late.
            !SasDerivation.commit(nonce, crypto).contentEquals(committed) ->
                progress.reject(EnrollmentFailure.MISMATCH)

            !recordValid -> progress.reject(EnrollmentFailure.PROTOCOL_ERROR)
            else -> {
                displayerNonce = nonce
                if (expectsRecord) peer = joiner
                showSas()
            }
        }
    }

    /** Shows the digits; the user of this device confirms them, or rejects. */
    private fun showSas(): List<EnrollmentEffect> {
        val digits = SasDerivation.sas(
            psk = checkNotNull(code).psk,
            handshakeHash = checkNotNull(handshakeHash),
            staticA = checkNotNull(P2pEncoding.base64UrlOrNull(config.device.noisePub)),
            staticB = checkNotNull(progress.remoteStatic),
            scannerNonce = checkNotNull(scannerNonce),
            displayerNonce = checkNotNull(displayerNonce),
            crypto = crypto,
            scope = config.scope,
        )
        progress.enter(EnrollmentPhase.AWAITING_APPROVAL, now + config.timings.approvalTimeoutMs)
        return listOf(EnrollmentEffect.ShowSas(digits, checkNotNull(peer)))
    }

    private fun onApproved(bundle: EnrollmentBundle): List<EnrollmentEffect> {
        val joiner = peer
        val admitsJoiner = joiner != null && bundle.admits(joiner, crypto::sha256)
        val bytes = EnrollmentBundleCodec.encode(bundle)
        return when {
            progress.phase != EnrollmentPhase.AWAITING_APPROVAL || config.role != EnrollmentRole.MEMBER -> emptyList()
            !admitsJoiner -> progress.reject(EnrollmentFailure.PROTOCOL_ERROR)
            // The joiner hears why, so both devices show the same error.
            bytes.size > minOf(config.maxBundleSize, FrameType.ENROLL_BUNDLE.maxSize) ||
                !EnrollmentBundleCodec.limits.admits(bytes) ->
                progress.reject(EnrollmentFailure.TOO_LARGE)

            peerConfirmed -> sendBundle(bundle, bytes)
            else -> {
                // The joiner's user has not confirmed yet; the approval window still runs.
                approved = bundle to bytes
                progress.enter(EnrollmentPhase.AWAITING_PEER_APPROVAL, progress.deadline)
                emptyList()
            }
        }
    }

    private fun onConfirmed(): List<EnrollmentEffect> =
        if (progress.phase == EnrollmentPhase.AWAITING_APPROVAL && config.role == EnrollmentRole.JOINER) {
            // The member's user may still be comparing the digits.
            val timings = config.timings
            progress.enter(EnrollmentPhase.AWAITING_BUNDLE, now + timings.approvalTimeoutMs + timings.bundleTimeoutMs)
            progress.send(FrameType.ENROLL_CONFIRM, EMPTY_PAYLOAD)
        } else {
            emptyList()
        }

    private fun onPeerConfirmed(): List<EnrollmentEffect> = when {
        config.role != EnrollmentRole.MEMBER -> progress.reject(EnrollmentFailure.PROTOCOL_ERROR)
        progress.phase == EnrollmentPhase.AWAITING_APPROVAL -> {
            peerConfirmed = true
            emptyList()
        }

        progress.phase == EnrollmentPhase.AWAITING_PEER_APPROVAL -> {
            val (bundle, bytes) = checkNotNull(approved)
            sendBundle(bundle, bytes)
        }

        // A joiner can not confirm digits it was never shown.
        else -> progress.reject(EnrollmentFailure.PROTOCOL_ERROR)
    }

    /**
     * Both users confirmed: the member sends the [bytes] of the [bundle], and stores the
     * admission in it once the joiner acknowledges it, see [onAck].
     */
    private fun sendBundle(bundle: EnrollmentBundle, bytes: ByteArray): List<EnrollmentEffect> {
        approved = null
        sent = bundle
        val until = now + config.timings.bundleTimeoutMs + config.timings.ackTimeoutMs
        progress.enter(EnrollmentPhase.AWAITING_ACK, until)
        return progress.send(FrameType.ENROLL_BUNDLE, bytes)
    }

    /** The joiner stored the bundle: the member stores the admission in it, and is done. */
    private fun onAck(): List<EnrollmentEffect> {
        val bundle = sent
        return if (progress.phase == EnrollmentPhase.AWAITING_ACK && bundle != null) {
            sent = null
            listOf(EnrollmentEffect.SaveAdmission(bundle)) + progress.finish()
        } else {
            emptyList()
        }
    }

    private fun onBundle(bundle: EnrollmentBundle?): List<EnrollmentEffect> =
        when {
            // A bundle before the joiner's user confirmed is never stored.
            config.role != EnrollmentRole.JOINER || progress.phase != EnrollmentPhase.AWAITING_BUNDLE ->
                progress.reject(EnrollmentFailure.PROTOCOL_ERROR)

            bundle == null || !bundle.admits(config.device, crypto::sha256) ->
                progress.reject(EnrollmentFailure.PROTOCOL_ERROR)

            else -> listOf(EnrollmentEffect.PersistBundle(bundle)) +
                progress.send(FrameType.ENROLL_ACK, EMPTY_PAYLOAD) +
                progress.finish()
        }

    private fun onTick(now: Long): List<EnrollmentEffect> {
        this.now = now
        // A scanner that never got through most likely reached a displayer whose code expired.
        val inviteExpired = progress.phase == EnrollmentPhase.AWAITING_HANDSHAKE &&
            (isDisplayer || code?.isExpired(now) == true)
        return when {
            now < progress.deadline -> emptyList()
            inviteExpired -> progress.fail(EnrollmentFailure.EXPIRED)
            else -> progress.reject(EnrollmentFailure.TIMEOUT)
        }
    }

    private companion object {
        /** The size of a SHA-256 commitment. */
        const val COMMIT_SIZE = 32

        const val MILLIS_PER_SECOND = 1_000L

        val EMPTY_PAYLOAD: ByteArray = "{}".encodeToByteArray()
    }
}

internal enum class EnrollmentPhase {
    NEW,
    AWAITING_CODE,
    AWAITING_HANDSHAKE,
    AWAITING_HELLO,
    AWAITING_REVEAL,
    AWAITING_APPROVAL,
    AWAITING_PEER_APPROVAL,
    AWAITING_BUNDLE,
    AWAITING_ACK,
    DONE,
    FAILED,
}

/** The phase, deadline and channel of an enrollment, and the effects that end it. */
internal class EnrollmentProgress {
    private val outgoing = OutgoingMessages()

    var phase: EnrollmentPhase = EnrollmentPhase.NEW
        private set
    var deadline: Long = Long.MAX_VALUE
        private set

    /** `true` once a handshake finished, so the other device can be told about a failure. */
    var connected: Boolean = false
    var remoteStatic: ByteArray? = null

    val isFinished: Boolean get() = phase == EnrollmentPhase.DONE || phase == EnrollmentPhase.FAILED

    fun enter(next: EnrollmentPhase, until: Long) {
        phase = next
        deadline = until
    }

    fun send(type: FrameType, bytes: ByteArray): List<EnrollmentEffect> =
        outgoing.frames(type, bytes).map { EnrollmentEffect.SendFrame(it) }

    /** Fails, telling the other device if the channel is up. */
    fun reject(reason: EnrollmentFailure): List<EnrollmentEffect> {
        val notice = if (connected) {
            val payload = EnrollRejectPayload(reason.name)
            send(FrameType.ENROLL_REJECT, EnrollRejectPayload.serializer().encodePayload(payload))
        } else {
            emptyList()
        }
        return notice + fail(reason)
    }

    fun fail(reason: EnrollmentFailure): List<EnrollmentEffect> {
        enter(EnrollmentPhase.FAILED, Long.MAX_VALUE)
        return listOf(EnrollmentEffect.Failed(reason))
    }

    fun finish(): List<EnrollmentEffect> {
        enter(EnrollmentPhase.DONE, Long.MAX_VALUE)
        return listOf(EnrollmentEffect.Done)
    }
}

/**
 * Returns the failure an ENROLL_REJECT gave, this one as its sender sees it, as its receiver
 * sees it: a reason about one side is mirrored, and an unknown one is a rejection.
 */
private fun EnrollmentFailure?.seenByReceiver(): EnrollmentFailure = when (this) {
    null,
    EnrollmentFailure.REJECTED,
    -> EnrollmentFailure.REJECTED_BY_PEER

    EnrollmentFailure.UPDATE_REQUIRED -> EnrollmentFailure.PEER_UPDATE_REQUIRED
    EnrollmentFailure.PEER_UPDATE_REQUIRED -> EnrollmentFailure.UPDATE_REQUIRED
    else -> this
}

/** `true` if this record is of the [other] device: the same id and Noise key. */
private fun RosterDevice.isSameDevice(other: RosterDevice): Boolean = id == other.id && noisePub == other.noisePub
