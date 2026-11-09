package com.artemchep.keyguard.feature.p2psync.enroll

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.model.PairingCode
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.session.FrameCodec
import com.artemchep.keyguard.feature.p2psync.session.FrameType
import com.artemchep.keyguard.feature.p2psync.session.ReassemblyResult
import com.artemchep.keyguard.feature.p2psync.session.Reassembler
import com.artemchep.keyguard.feature.p2psync.session.decodePayloadOrNull
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestDeviceKeys
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random

/** One enrolling device: its machine and everything it asked for. */
class EnrollingDevice(
    val keys: TestDeviceKeys,
    val config: EnrollmentConfig,
    crypto: TestCrypto,
) {
    val machine = EnrollmentStateMachine(config, crypto)
    val effects = mutableListOf<EnrollmentEffect>()
    val outbox = ArrayDeque<ByteArray>()

    fun handle(effects: List<EnrollmentEffect>) {
        this.effects += effects
        effects.filterIsInstance<EnrollmentEffect.SendFrame>().forEach { outbox.addLast(it.bytes) }
    }

    fun on(event: EnrollmentEvent) = handle(machine.on(event))

    inline fun <reified T : EnrollmentEffect> find(): T? = effects.filterIsInstance<T>().firstOrNull()

    fun sentTypes(): List<FrameType?> = effects.filterIsInstance<EnrollmentEffect.SendFrame>().map { it.frame.type }

    /** The ENROLL_REVEAL this device sent, if any. */
    internal fun sentReveal(): EnrollRevealPayload? = effects
        .filterIsInstance<EnrollmentEffect.SendFrame>()
        .firstOrNull { it.frame.type == FrameType.ENROLL_REVEAL }
        ?.let { (Reassembler().accept(it.frame) as? ReassemblyResult.Complete)?.message?.payload }
        ?.let { EnrollRevealPayload.serializer().decodePayloadOrNull(it) }
}

/**
 * A member (the group founder) and a joiner, wired together: one shows the code, the other
 * scans it, and frames move between them on [deliver]. The member names its group in its
 * ENROLL_HELLO unless [memberNamesGroup] is `false`. [memberConfig] and [joinerConfig] change
 * the setup of each device, such as the versions it speaks.
 */
class EnrollmentHarness(
    memberDisplays: Boolean,
    val crypto: TestCrypto = TestCrypto(3),
    random: Random = Random(4),
    maxBundleSize: Int = EnrollmentConfig.MAX_BUNDLE_SIZE,
    memberNamesGroup: Boolean = true,
    memberConfig: (EnrollmentConfig) -> EnrollmentConfig = { it },
    joinerConfig: (EnrollmentConfig) -> EnrollmentConfig = { it },
) {
    val group = TestGroup(crypto, size = 1, random = random)
    private val joinerKeys = group.newKeys(random)
    var now = 10_000L

    val member = EnrollingDevice(
        keys = group.founder,
        config = EnrollmentConfig(
            role = EnrollmentRole.MEMBER,
            display = if (memberDisplays) DisplayMode.DISPLAYER else DisplayMode.SCANNER,
            device = group.roster.devices.single(),
            group = group.groupId.takeIf { memberNamesGroup },
            relay = "wss://relay.test",
            scope = SyncScope.APP,
            maxBundleSize = maxBundleSize,
        ).let(memberConfig),
        crypto = crypto,
    )

    val joiner = EnrollingDevice(
        keys = joinerKeys,
        config = EnrollmentConfig(
            role = EnrollmentRole.JOINER,
            display = if (memberDisplays) DisplayMode.SCANNER else DisplayMode.DISPLAYER,
            device = group.record(joinerKeys, by = group.founder, at = Hlc(now, 0, group.founder.id)),
            scope = SyncScope.APP,
        ).let(joinerConfig),
        crypto = crypto,
    )

    val displayer: EnrollingDevice get() = if (member.config.display == DisplayMode.DISPLAYER) member else joiner
    val scanner: EnrollingDevice get() = if (displayer === member) joiner else member

    /** Shows and scans the code; returns it. */
    fun showAndScan(): PairingCode {
        displayer.handle(displayer.machine.start(now))
        scanner.handle(scanner.machine.start(now))
        val code = requireNotNull(displayer.find<EnrollmentEffect.ShowCode>()).code
        scanner.on(EnrollmentEvent.CodeScanned(code, now))
        return code
    }

    /** Finishes the Noise handshake on both sides; a relay in the middle means two different hashes. */
    fun handshake(displayerHash: ByteArray = ByteArray(32) { 1 }, scannerHash: ByteArray = displayerHash) {
        displayer.on(EnrollmentEvent.HandshakeDone(scanner.keys.noisePublicBytes, displayerHash))
        scanner.on(EnrollmentEvent.HandshakeDone(displayer.keys.noisePublicBytes, scannerHash))
    }

    /** Moves frames both ways until both outboxes are empty. */
    fun deliver(drop: (ByteArray) -> Boolean = { false }) {
        while (member.outbox.isNotEmpty() || joiner.outbox.isNotEmpty()) {
            member.outbox.removeFirstOrNull()?.takeUnless(drop)?.let { joiner.on(EnrollmentEvent.Frame(it)) }
            joiner.outbox.removeFirstOrNull()?.takeUnless(drop)?.let { member.on(EnrollmentEvent.Frame(it)) }
        }
    }

    /**
     * Hands the joiner [bundle] as the member would, with its JSON changed by [patch], such
     * as by a newer build.
     */
    fun sendBundle(bundle: EnrollmentBundle, patch: (JsonObject) -> JsonObject) {
        val json = P2pJson.encodeToJsonElement(EnrollmentBundle.serializer(), bundle).jsonObject
        val payload = P2pJson.encodeToString(JsonObject.serializer(), patch(json))
        FrameCodec.split(FrameType.ENROLL_BUNDLE, msgId = 1_000_000, payload = payload.encodeToByteArray())
            .forEach { joiner.on(EnrollmentEvent.Frame(FrameCodec.encode(it))) }
        deliver()
    }

    /** The bundle that admits the joiner, as the member's app would build it. */
    fun bundle(): EnrollmentBundle {
        val admitted = group.signer.admit(joiner.config.device, group.founder.authorityPrivate)
        val roster = group.roster.copy(devices = group.roster.devices + admitted)
        return EnrollmentBundle(
            group = group.groupId,
            groupKey = "AAAA",
            roster = roster,
            entries = emptyList(),
        )
    }
}
