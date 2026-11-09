package com.artemchep.keyguard.provider.keyguard.enroll

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.auth.keyguard.pairing.KeyguardPairing
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.enroll.DisplayMode
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentBundle
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentConfig
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentEffect
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentEvent
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentFailure
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentRole
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentStateMachine
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentTimings
import com.artemchep.keyguard.feature.p2psync.enroll.chooseEnrollmentRole
import com.artemchep.keyguard.feature.p2psync.model.PairingCode
import com.artemchep.keyguard.feature.p2psync.model.PairingCodeDecoding
import com.artemchep.keyguard.feature.p2psync.model.PairingCodeError
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.feature.p2psync.session.FrameType
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKey
import com.artemchep.keyguard.provider.keyguard.KeyguardPasswordPrompt
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkService
import com.artemchep.keyguard.provider.keyguard.noisePrivateKey
import com.artemchep.keyguard.provider.keyguard.session.KeyguardPeerSession
import com.artemchep.keyguard.provider.keyguard.session.KeyguardRoomConnector
import com.artemchep.keyguard.provider.keyguard.session.KeyguardRoomSpec
import com.artemchep.keyguard.provider.keyguard.signPrivateKey
import com.artemchep.keyguard.provider.keyguard.toRosterDevice
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayDefaults
import com.artemchep.keyguard.provider.keyguard.transport.NoiseByteStream
import com.artemchep.keyguard.provider.keyguard.transport.NoiseSuite
import com.artemchep.keyguard.provider.keyguard.transport.NoisePinException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pairs this device with another one: links this app with another one, or joins the link
 * of another app. Either side may show the code; the other scans it. [pairApp] picks which
 * of the two devices admits the other one, see [chooseEnrollmentRole].
 *
 * The displayer creates a one-time code (3 minutes, single use); both devices meet in the
 * relay room derived from its pre-shared key, connect over the first transport that
 * opens (WebRTC with a head start, and relay forwarding), run Noise XXpsk3 and agree on
 * six digits. The scanner shows them; the displayer's user types them, then the
 * scanner's user confirms that both devices show the same, see [EnrollmentStep.Verify].
 * Once both users confirmed, the member sends the group's data, which the joiner stores and
 * acknowledges; only then does the member store the admission of the joiner, see
 * [EnrollmentEffect.SaveAdmission]. Any failure burns the code.
 *
 * @param appLink founds the link of this app when it admits another one, see [pairApp].
 */
// A holder of what pairing needs.
@Suppress("LongParameterList")
class KeyguardEnrollmentCoordinator(
    private val roomConnector: KeyguardRoomConnector,
    private val vault: KeyguardEnrollmentVault,
    private val appLink: AppLinkService,
    private val crypto: P2pCrypto,
    private val timings: EnrollmentTimings = DEFAULT_TIMINGS,
    private val now: () -> Instant = { Clock.System.now() },
) {
    companion object {
        /** How long the devices may take to connect and finish the handshake. */
        const val CONNECT_TIMEOUT_MS: Long = 60_000L

        /** The waits of an enrollment. */
        val DEFAULT_TIMINGS: EnrollmentTimings = EnrollmentTimings(handshakeTimeoutMs = CONNECT_TIMEOUT_MS)

        /** How long one frame may wait to leave: a peer that stops reading fails the pairing. */
        const val WRITE_TIMEOUT_MS: Long = 30_000L
    }

    /**
     * Starts pairing this app with the app on another device; it runs in [scope]. An app
     * linked to other devices keeps its link and admits the other app; of two apps that are
     * not, the one that scans the code founds a link as the device named [deviceLabel], and
     * the other joins it, each with its [network] settings, see [chooseEnrollmentRole]. Two
     * apps linked to other devices can not pair. Either way the pairing needs the master
     * password, asked with [password] once it starts.
     */
    fun pairApp(
        deviceLabel: String,
        network: P2pNetwork,
        password: KeyguardPasswordPrompt,
        scope: CoroutineScope,
    ): KeyguardEnrollment {
        val run = EnrollmentRun(SyncScope.APP, password) { scanned ->
            when (chooseEnrollmentRole(isMember = appLink.isLinked(), scanned = scanned)) {
                // The member of the link of this app; one that is not linked yet founds it.
                EnrollmentRole.MEMBER -> MemberSide(
                    groupId = P2pGroupId.AppLink,
                    prepareGroup = { pw -> appLink.prepareLink(deviceLabel, network, pw) },
                )

                EnrollmentRole.JOINER -> JoinerSide(deviceLabel, network, SyncScope.APP)
                // Both apps are linked: to each other, or to other devices.
                null -> throw if (appLink.isLinkedWith(checkNotNull(scanned).pub)) {
                    KeyguardPairingException(
                        error = KeyguardPairingError.LINKED_TOGETHER,
                        message = "The apps are linked to each other already.",
                    )
                } else {
                    KeyguardPairingException(
                        error = KeyguardPairingError.ALREADY_LINKED,
                        message = "Both apps are linked to other devices.",
                    )
                }
            }
        }
        scope.launch { run.run() }
        return KeyguardEnrollment(run)
    }

    /** This device in an enrollment. */
    internal class Identity(
        val deviceId: String,
        val noisePrivateKey: ByteArray,
        /** The record the code and the state machine start from. */
        val record: RosterDevice,
        val network: P2pNetwork,
        /** The id of the sync group of a member; `null` for a joiner. */
        val groupId: String? = null,
        /**
         * Returns the joiner's record to announce to the device [peerId] in the group
         * [groupId], which the member names; `null` for a member.
         */
        val announce: ((peerId: String, groupId: String) -> RosterDevice)? = null,
    )

    /** The role-specific part of an enrollment. */
    internal interface Side {
        val role: EnrollmentRole

        /** Gets this device ready with the [masterPassword]. */
        suspend fun prepare(masterPassword: String): Identity

        /** The member admits [peer] and returns the bundle; nothing is stored yet. */
        suspend fun admit(peer: RosterDevice): EnrollmentBundle

        /** The member stores the admission of the [bundle] of [admit], once the joiner acknowledged it. */
        suspend fun saveAdmission(bundle: EnrollmentBundle)

        /** The joiner stores [bundle] and returns its new group. */
        suspend fun persist(bundle: EnrollmentBundle): P2pGroupId

        /** Returns the group on this device once the enrollment succeeded. */
        suspend fun complete(): P2pGroupId

        /** Forgets the secrets of the enrollment once it ended. */
        fun close() = Unit
    }

    /**
     * The master password opens the authority key that admits the joiner; the key stays
     * open until the enrollment ends.
     *
     * @param groupId the group the joiner joins.
     * @param prepareGroup creates the group if this device holds none yet, before the
     *   enrollment starts, with the master password; returns the open authority key of a
     *   group it created.
     */
    private inner class MemberSide(
        private val groupId: P2pGroupId,
        private val prepareGroup: suspend (String) -> KeyguardAuthorityKey?,
    ) : Side {
        private lateinit var member: KeyguardEnrollmentVault.Member

        private var authority: KeyguardAuthorityKey? = null

        /** The admission the user approved, signed and held until the joiner acknowledged it. */
        private var admission: KeyguardEnrollmentVault.Admission? = null

        override val role: EnrollmentRole = EnrollmentRole.MEMBER

        override suspend fun prepare(masterPassword: String): Identity {
            // A group created just now hands over its key: opening it again would derive its sealing key twice.
            authority = prepareGroup(masterPassword)
            member = vault.member(groupId)
            if (authority == null) authority = vault.openAuthority(member, masterPassword)
            val device = member.membership.device
            return Identity(
                deviceId = device.deviceId,
                noisePrivateKey = device.noisePrivateKey(),
                record = member.record,
                network = member.membership.network,
                groupId = member.membership.groupId,
            )
        }

        override suspend fun admit(peer: RosterDevice): EnrollmentBundle {
            val signed = vault.admit(member, peer, checkNotNull(authority) { "The authority key is not open." })
            admission = signed
            return signed.bundle
        }

        override suspend fun saveAdmission(bundle: EnrollmentBundle) {
            val signed = checkNotNull(admission?.takeIf { it.bundle === bundle }) {
                "The bundle was not approved."
            }
            vault.saveAdmission(member, signed)
        }

        override suspend fun persist(bundle: EnrollmentBundle): P2pGroupId =
            error("The member does not receive a bundle.")

        override suspend fun complete(): P2pGroupId {
            // The other devices learn about the new one from the next exchange.
            runCatchingUnlessCancelled { vault.announce(groupId) }
            return groupId
        }

        override fun close() {
            authority?.close()
            authority = null
        }
    }

    /**
     * The master password seals the new authority key.
     *
     * @param syncScope what the sync group the joiner joins syncs.
     */
    private inner class JoinerSide(
        private val deviceLabel: String,
        private val network: P2pNetwork,
        private val syncScope: SyncScope,
    ) : Side {
        private lateinit var device: P2pDevice

        private var groupId: P2pGroupId? = null

        override val role: EnrollmentRole = EnrollmentRole.JOINER

        override suspend fun prepare(masterPassword: String): Identity {
            vault.checkJoinable(syncScope)
            device = vault.newDevice(deviceLabel, masterPassword)
            val signKey = device.signPrivateKey()
            return Identity(
                deviceId = device.deviceId,
                noisePrivateKey = device.noisePrivateKey(),
                // The record names its inviter, which this device learns once they meet,
                // and is signed for the group, which the member names.
                record = device.toRosterDevice(addedAt = Hlc.MIN, addedBy = ""),
                network = network,
                announce = { inviterId, groupId ->
                    val record = device.toRosterDevice(
                        addedAt = Hlc(wall = now().toEpochMilliseconds(), counter = 0, node = inviterId),
                        addedBy = inviterId,
                    )
                    RosterSigner(crypto, groupId).selfSign(record, signKey)
                },
            )
        }

        override suspend fun admit(peer: RosterDevice): EnrollmentBundle =
            error("The joiner does not admit devices.")

        override suspend fun saveAdmission(bundle: EnrollmentBundle) =
            error("The joiner does not admit devices.")

        override suspend fun persist(bundle: EnrollmentBundle): P2pGroupId = vault
            .import(bundle, device, network, syncScope)
            .also { groupId = it }

        override suspend fun complete(): P2pGroupId = checkNotNull(groupId) {
            "The group was not stored."
        }
    }

    /**
     * One enrollment: its state machine, its connection and the commands of its user. It asks
     * for the master password with [password] once it starts, then picks its [Side] with
     * [choose]: the scanner once it read the code of the displayer, see [chooseEnrollmentRole].
     *
     * @param syncScope what the sync group syncs; a code of another scope is turned down.
     */
    // One function per step of the enrollment.
    @Suppress("TooManyFunctions")
    internal inner class EnrollmentRun(
        private val syncScope: SyncScope,
        private val password: KeyguardPasswordPrompt,
        private val choose: suspend (scanned: PairingCode?) -> Side,
    ) {
        private val stepSink = MutableStateFlow<EnrollmentStep>(EnrollmentStep.Method)

        val step: StateFlow<EnrollmentStep> = stepSink.asStateFlow()

        private val commands = Channel<Command>(Channel.UNLIMITED)

        /** Guards the [machine]; held only while it takes an event, never during I/O. */
        private val mutex = Mutex()

        /** The effects of the [machine], in the order it made them, see [perform]. */
        private val effects = Channel<List<EnrollmentEffect>>(Channel.UNLIMITED)

        private val display = CompletableDeferred<DisplayMode>()

        private val code = CompletableDeferred<PairingCode>()

        /** The code the scanner read, before it picked its [side]. */
        private val scannedCode = CompletableDeferred<PairingCode>()

        /** What this device does in the enrollment, once it is picked. */
        private var side: Side? = null

        private val chosenSide: Side get() = checkNotNull(side) { "The role of this device is not picked yet." }

        private val finished = CompletableDeferred<Unit>()

        private var machine: EnrollmentStateMachine? = null

        private var stream: NoiseByteStream? = null

        private var peer: RosterDevice? = null

        /** The step of a rejection this device made for its own reason. */
        private var rejection: KeyguardPairingError = KeyguardPairingError.CANCELLED

        fun send(command: Command) {
            commands.trySend(command)
        }

        suspend fun run() {
            try {
                coroutineScope {
                    launch { for (command in commands) onCommand(command) }
                    launch { perform() }
                    launch { drive() }
                    finished.await()
                    // Let the last frames, such as an acknowledgement, reach the other device.
                    delay(LINGER_MS)
                    coroutineContext.cancelChildren()
                }
            } finally {
                finish(EnrollmentStep.Failed(KeyguardPairingError.CANCELLED))
                side?.close()
                withContext(NonCancellable) { stream?.close() }
            }
        }

        private suspend fun drive() {
            val mode = display.await()
            var scanned: PairingCode? = null
            val prepared = runCatchingUnlessCancelled {
                val masterPassword = password() ?: throw passwordDenied()
                // The code of the displayer says whether it is a member.
                if (mode == DisplayMode.SCANNER) {
                    setStep(EnrollmentStep.Scanning)
                    scanned = scannedCode.await()
                }
                val chosen = choose(scanned)
                side = chosen
                chosen.prepare(masterPassword)
            }
            val identity = prepared.getOrNull()
                ?: return finish(failed(prepared.exceptionOrNull()))
            val config = EnrollmentConfig(
                role = chosenSide.role,
                display = mode,
                device = identity.record,
                group = identity.groupId,
                relay = identity.network.relayUrl,
                ice = identity.network.iceUrl,
                timings = timings,
                scope = syncScope,
            )
            mutex.withLock {
                val created = EnrollmentStateMachine(config, crypto)
                machine = created
                dispatch(created.start(nowMs()))
            }
            scanned?.let { event(EnrollmentEvent.CodeScanned(it, nowMs())) }
            coroutineScope {
                launch { tick() }
                connect(code.await(), identity, mode)
            }
        }

        private suspend fun tick() {
            while (true) {
                delay(KeyguardPeerSession.TICK_MS)
                event(EnrollmentEvent.Tick(nowMs()))
            }
        }

        private suspend fun onCommand(command: Command) {
            when (command) {
                is Command.Method -> display.complete(command.display)
                is Command.Code -> if (stepSink.value == EnrollmentStep.Scanning) scanned(command.text)
                Command.Approve -> {
                    // The scanner's user compared the digits; the displayer's types them.
                    val verify = stepSink.value as? EnrollmentStep.Verify
                    if (verify?.display == DisplayMode.SCANNER && !verify.confirmed) confirm()
                }

                is Command.Digits -> enterDigits(command.digits)
                Command.Reject -> if (machine == null) {
                    finish(EnrollmentStep.Failed(KeyguardPairingError.CANCELLED))
                } else {
                    event(EnrollmentEvent.Rejected)
                }
            }
        }

        private suspend fun scanned(text: String) {
            when (val decoded = PairingCode.decode(text, expected = syncScope)) {
                is PairingCodeDecoding.Valid -> {
                    setStep(EnrollmentStep.Connecting)
                    scannedCode.complete(decoded.code)
                }
                is PairingCodeDecoding.Invalid -> {
                    val reason = when (decoded.error) {
                        PairingCodeError.NEWER_VERSION -> KeyguardPairingError.UPDATE_REQUIRED
                        PairingCodeError.OLDER_VERSION -> KeyguardPairingError.PEER_UPDATE_REQUIRED
                        PairingCodeError.WRONG_SCOPE -> KeyguardPairingError.WRONG_KIND
                        PairingCodeError.WRONG_PREFIX,
                        PairingCodeError.MALFORMED,
                        -> KeyguardPairingError.INVALID_CODE
                    }
                    finish(EnrollmentStep.Failed(reason))
                }
            }
        }

        /**
         * The displayer's user typed the [text] of the digits the other device shows: the
         * right digits confirm them, others count as a miss. The digits never change, so
         * typing again tells an attacker nothing.
         */
        private suspend fun enterDigits(text: String) {
            val verify = stepSink.value as? EnrollmentStep.Verify
            if (verify?.display != DisplayMode.DISPLAYER || verify.confirmed) return
            if (text.filterNot(Char::isWhitespace) == verify.sas) {
                confirm()
            } else {
                updateVerify { it.copy(misses = it.misses + 1) }
            }
        }

        /**
         * This device's user confirmed the digits, once: the joiner tells the member, which
         * sends the data once its user approved too; the member admits the joiner in memory,
         * and stores the admission once the joiner acknowledged the data, see
         * [EnrollmentEffect.SaveAdmission].
         */
        private suspend fun confirm() {
            val joiner = peer
            // The machine, not only the screen, must still wait for this user.
            val awaiting = joiner != null && mutex.withLock { machine?.awaitsApproval == true }
            if (!awaiting) return
            updateVerify { it.copy(confirmed = true) }
            when (chosenSide.role) {
                EnrollmentRole.JOINER -> event(EnrollmentEvent.Confirmed)
                EnrollmentRole.MEMBER -> {
                    val bundle = runCatchingUnlessCancelled { chosenSide.admit(checkNotNull(joiner)) }.getOrNull()
                    if (bundle != null) {
                        event(EnrollmentEvent.Approved(bundle))
                    } else {
                        rejection = KeyguardPairingError.MISMATCH
                        event(EnrollmentEvent.Rejected)
                    }
                }
            }
        }

        /** Meets the other device of [pairingCode] and feeds the machine until the end. */
        private suspend fun connect(
            pairingCode: PairingCode,
            identity: Identity,
            mode: DisplayMode,
        ) {
            val spec = KeyguardRoomSpec(
                endpoint = KeyguardRelayDefaults.endpointOfCode(
                    relayUrl = pairingCode.relay,
                    iceUrl = pairingCode.ice,
                    network = identity.network,
                ),
                room = roomConnector.inviteRoom(pairingCode.psk),
                localDeviceId = identity.deviceId,
                allowLocalNetwork = identity.network.allowLan,
            )
            val scanner = mode == DisplayMode.SCANNER
            runCatchingUnlessCancelled {
                appLink.requireCanLink(identity.network.copy(relayUrl = pairingCode.relay))
                roomConnector.join(spec) { room ->
                    // Only the two devices of the code know the room.
                    val peerId = room.signaling.presence.first { it.isNotEmpty() }.first()
                    val role = if (scanner) ConnectRole.INITIATOR else ConnectRole.RESPONDER
                    val selected = room.connect(peerId, role)
                    val noise = NoiseByteStream.handshake(
                        stream = selected.stream,
                        suite = NoiseSuite.XX_PSK3,
                        initiator = scanner,
                        localPrivateKey = identity.noisePrivateKey,
                        psk = pairingCode.psk,
                        // The scanner meets the displayer of the code, or nobody.
                        pinnedRemoteStatic = pairingCode.pub.takeIf { scanner },
                    )
                    stream = noise
                    val announce = identity.announce
                    val done = EnrollmentEvent.HandshakeDone(
                        remoteStatic = noise.remoteStatic,
                        handshakeHash = noise.handshakeHash,
                        announce = announce?.let { sign -> { groupId -> sign(peerId, groupId) } },
                    )
                    event(done)
                    while (true) {
                        val bytes = noise.read() ?: break
                        event(EnrollmentEvent.Frame(bytes))
                    }
                }
            }.onFailure { e ->
                // Another device answered under the code: the keys differ, as the digits would.
                if (e is NoisePinException) finish(EnrollmentStep.Failed(KeyguardPairingError.MISMATCH))
            }
            // The devices lost each other before the end.
            finish(EnrollmentStep.Failed(KeyguardPairingError.NETWORK))
        }

        private suspend fun event(event: EnrollmentEvent) {
            mutex.withLock {
                val current = machine ?: return@withLock
                dispatch(current.on(event))
            }
        }

        /**
         * Queues the [batch] of effects for [perform]. A failure ends the enrollment at once:
         * a deadline or a rejection must not wait behind a frame that can not leave.
         */
        private fun dispatch(batch: List<EnrollmentEffect>) {
            if (batch.isEmpty()) return
            batch.filterIsInstance<EnrollmentEffect.Failed>().firstOrNull()?.let { failed ->
                finish(EnrollmentStep.Failed(failed.reason.toPairingError(rejection)))
            }
            effects.trySend(batch)
        }

        /** Carries out the effects of the machine, one batch at a time, in order. */
        private suspend fun perform() {
            for (batch in effects) handle(batch)
        }

        private suspend fun handle(effects: List<EnrollmentEffect>) {
            for (effect in effects) {
                val proceed = when (effect) {
                    is EnrollmentEffect.ShowCode -> {
                        code.complete(effect.code)
                        setStep(
                            EnrollmentStep.ShowCode(
                                code = effect.code.encode(),
                                expiresAt = Instant.fromEpochMilliseconds(effect.code.expiresAtMillis),
                            ),
                        )
                        true
                    }

                    is EnrollmentEffect.Connect -> {
                        code.complete(effect.code)
                        setStep(EnrollmentStep.Connecting)
                        true
                    }

                    is EnrollmentEffect.ShowSas -> {
                        peer = effect.peer
                        setStep(
                            EnrollmentStep.Verify(
                                sas = effect.digits,
                                peerLabel = effect.peer.label,
                                role = chosenSide.role,
                                display = display.await(),
                            ),
                        )
                        true
                    }

                    is EnrollmentEffect.SendFrame -> {
                        if (effect.frame.type == FrameType.ENROLL_BUNDLE) setStep(EnrollmentStep.Transferring)
                        write(effect.bytes)
                    }

                    is EnrollmentEffect.SaveAdmission -> saveAdmission(effect.bundle)
                    is EnrollmentEffect.PersistBundle -> persist(effect.bundle)
                    EnrollmentEffect.Done -> {
                        val completed = runCatchingUnlessCancelled { chosenSide.complete() }
                        finish(
                            completed.getOrNull()?.let { EnrollmentStep.Done(it) }
                                ?: EnrollmentStep.Failed(KeyguardPairingError.FAILED, completed.exceptionOrNull()),
                        )
                        true
                    }

                    is EnrollmentEffect.Failed -> {
                        finish(EnrollmentStep.Failed(effect.reason.toPairingError(rejection)))
                        true
                    }
                }
                // A frame that can not leave, or a bundle that can not be stored, ends it.
                if (!proceed) return
            }
        }

        private suspend fun write(bytes: ByteArray): Boolean {
            val current = stream ?: return false
            val written = runCatchingUnlessCancelled {
                withTimeoutOrNull(WRITE_TIMEOUT_MS) {
                    current.awaitWritable()
                    current.write(bytes)
                } != null
            }.getOrDefault(false)
            if (!written) finish(EnrollmentStep.Failed(KeyguardPairingError.NETWORK))
            return written
        }

        /**
         * Stores the admission in the [bundle] once the joiner acknowledged it. If it can not
         * be stored, such as when the roster changed meanwhile and no longer takes the
         * joiner, the pairing fails here, while the joiner already holds the group: see
         * [EnrollmentEffect.SaveAdmission].
         */
        private suspend fun saveAdmission(bundle: EnrollmentBundle): Boolean {
            val saved = runCatchingUnlessCancelled { chosenSide.saveAdmission(bundle) }
            saved.exceptionOrNull()?.let { e -> finish(failed(e, reason = KeyguardPairingError.FAILED)) }
            return saved.isSuccess
        }

        private suspend fun persist(bundle: EnrollmentBundle): Boolean {
            setStep(EnrollmentStep.Transferring)
            val stored = runCatchingUnlessCancelled { chosenSide.persist(bundle) }
            // Without the acknowledgement the member fails too.
            if (stored.isFailure) finish(failed(stored.exceptionOrNull()))
            return stored.isSuccess
        }

        private fun finish(step: EnrollmentStep) {
            if (finished.complete(Unit)) stepSink.value = step
        }

        /**
         * Shows the [step], unless the enrollment ended: an effect that waited behind a slow
         * write, or a late command, never revives a finished enrollment.
         */
        private fun setStep(step: EnrollmentStep) {
            stepSink.update { current -> if (current.isFinal) current else step }
        }

        /** Changes the [EnrollmentStep.Verify] step, if it is the current one. */
        private fun updateVerify(transform: (EnrollmentStep.Verify) -> EnrollmentStep.Verify) {
            stepSink.update { current -> if (current is EnrollmentStep.Verify) transform(current) else current }
        }

        private fun nowMs(): Long = now().toEpochMilliseconds()
    }

    internal sealed interface Command {
        data class Method(val display: DisplayMode) : Command

        data class Code(val text: String) : Command

        data object Approve : Command

        data class Digits(val digits: String) : Command

        data object Reject : Command
    }
}

/**
 * One pairing, as its screens drive it: pick [showCode] or [scanCode], hand over a
 * scanned code with [submitCode], then check the digits: the displayer [enterDigits], the
 * scanner [approve]s them; either may [reject] them. [step] tells where it is.
 */
class KeyguardEnrollment internal constructor(
    private val run: KeyguardEnrollmentCoordinator.EnrollmentRun,
) : KeyguardPairing {
    override val step: StateFlow<EnrollmentStep> get() = run.step

    /** This device shows the code. */
    override fun showCode() = run.send(KeyguardEnrollmentCoordinator.Command.Method(DisplayMode.DISPLAYER))

    /** This device scans, or pastes, the code of the other one. */
    override fun scanCode() = run.send(KeyguardEnrollmentCoordinator.Command.Method(DisplayMode.SCANNER))

    /** Hands over the [text] of the other device's code. */
    override fun submitCode(text: String) = run.send(KeyguardEnrollmentCoordinator.Command.Code(text))

    /**
     * The scanner's user saw the same digits on both devices; the displayer confirms with
     * [enterDigits] instead.
     */
    override fun approve() = run.send(KeyguardEnrollmentCoordinator.Command.Approve)

    /**
     * The displayer's user typed the [digits] the other device shows; the right ones
     * confirm them. See [EnrollmentStep.Verify].
     */
    override fun enterDigits(digits: String) = run.send(KeyguardEnrollmentCoordinator.Command.Digits(digits))

    /** The digits differ, or the user does not want the device. */
    override fun reject() = run.send(KeyguardEnrollmentCoordinator.Command.Reject)

    /** Stops the pairing, telling the other device. */
    override fun cancel() = reject()
}

/** `true` once the enrollment succeeded or failed. */
private val EnrollmentStep.isFinal: Boolean
    get() = this is EnrollmentStep.Done || this is EnrollmentStep.Failed

/** How long a finished enrollment stays connected so its last frames arrive. */
private const val LINGER_MS: Long = 1_000L

private fun EnrollmentFailure.toPairingError(rejection: KeyguardPairingError): KeyguardPairingError = when (this) {
    EnrollmentFailure.EXPIRED -> KeyguardPairingError.EXPIRED
    EnrollmentFailure.REJECTED -> rejection
    EnrollmentFailure.REJECTED_BY_PEER -> KeyguardPairingError.REJECTED
    EnrollmentFailure.MISMATCH,
    EnrollmentFailure.ROLE_MISMATCH,
    -> KeyguardPairingError.MISMATCH

    EnrollmentFailure.UPDATE_REQUIRED -> KeyguardPairingError.UPDATE_REQUIRED
    EnrollmentFailure.PEER_UPDATE_REQUIRED -> KeyguardPairingError.PEER_UPDATE_REQUIRED
    EnrollmentFailure.TIMEOUT -> KeyguardPairingError.NETWORK
    EnrollmentFailure.PROTOCOL_ERROR -> KeyguardPairingError.FAILED
    EnrollmentFailure.SCOPE_MISMATCH -> KeyguardPairingError.WRONG_KIND
    EnrollmentFailure.TOO_LARGE -> KeyguardPairingError.TOO_LARGE
}

/** The user did not give the master password: the pairing is cancelled. */
private fun passwordDenied() = KeyguardPairingException(
    error = KeyguardPairingError.CANCELLED,
    message = "The master password was not given.",
)

/**
 * Fails for the [reason], by default the error of a [KeyguardPairingException] [cause];
 * only an unexpected failure keeps its [cause], to say what went wrong.
 */
private fun failed(
    cause: Throwable?,
    reason: KeyguardPairingError = (cause as? KeyguardPairingException)?.error ?: KeyguardPairingError.FAILED,
) = EnrollmentStep.Failed(
    reason = reason,
    cause = cause.takeIf { reason == KeyguardPairingError.FAILED },
)
