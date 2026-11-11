package com.artemchep.keyguard.feature.p2psync.enroll

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.session.FrameCodec
import com.artemchep.keyguard.feature.p2psync.session.FrameType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnrollmentStateMachineTest {
    private fun happyPath(memberDisplays: Boolean, joinerConfirmsFirst: Boolean) {
        val pairing = EnrollmentHarness(memberDisplays)
        val code = pairing.showAndScan()
        assertEquals(memberDisplays, code.member)
        assertEquals(EnrollmentEffect.Connect(code), pairing.scanner.find<EnrollmentEffect.Connect>())
        pairing.handshake()
        pairing.deliver()

        val memberSas = assertNotNull(pairing.member.find<EnrollmentEffect.ShowSas>())
        val joinerSas = assertNotNull(pairing.joiner.find<EnrollmentEffect.ShowSas>())
        assertEquals(memberSas.digits, joinerSas.digits)
        assertEquals(pairing.joiner.config.device, memberSas.peer)
        assertEquals(pairing.member.config.device, joinerSas.peer)
        // The displayer committed to its nonce before the scanner sent its own.
        assertEquals(
            listOf(FrameType.ENROLL_HELLO, FrameType.ENROLL_REVEAL),
            pairing.displayer.sentTypes(),
        )
        assertEquals(listOf(FrameType.ENROLL_HELLO), pairing.scanner.sentTypes())
        assertEquals(EnrollmentVersion.CURRENT, pairing.member.machine.version)
        assertEquals(EnrollmentVersion.CURRENT, pairing.joiner.machine.version)

        // Nothing moves before the users of both devices confirm.
        val bundle = pairing.bundle()
        if (joinerConfirmsFirst) {
            pairing.joiner.on(EnrollmentEvent.Confirmed)
        } else {
            pairing.member.on(EnrollmentEvent.Approved(bundle))
        }
        pairing.deliver()
        assertTrue(FrameType.ENROLL_BUNDLE !in pairing.member.sentTypes())
        assertNull(pairing.member.find<EnrollmentEffect.SaveAdmission>())
        assertNull(pairing.joiner.find<EnrollmentEffect.PersistBundle>())
        if (joinerConfirmsFirst) {
            pairing.member.on(EnrollmentEvent.Approved(bundle))
        } else {
            pairing.joiner.on(EnrollmentEvent.Confirmed)
        }
        pairing.deliver()

        // The bundle leaves once both confirmed; the member stores the admission only once
        // the joiner stored the bundle and acknowledged it.
        val saved = assertNotNull(pairing.member.find<EnrollmentEffect.SaveAdmission>())
        assertEquals(bundle, saved.bundle)
        val sent = pairing.member.effects.indexOfFirst {
            it is EnrollmentEffect.SendFrame && it.frame.type == FrameType.ENROLL_BUNDLE
        }
        assertTrue(sent in 0 until pairing.member.effects.indexOf(saved))
        assertEquals(listOf(saved, EnrollmentEffect.Done), pairing.member.effects.takeLast(2))
        assertEquals(bundle, assertNotNull(pairing.joiner.find<EnrollmentEffect.PersistBundle>()).bundle)
        assertEquals(EnrollmentEffect.Done, pairing.joiner.effects.last())
        assertTrue(pairing.member.machine.isFinished && pairing.joiner.machine.isFinished)
    }

    @Test
    fun `the member shows the code and the joiner scans it`() =
        happyPath(memberDisplays = true, joinerConfirmsFirst = false)

    @Test
    fun `the joiner shows the code and the member scans it`() =
        happyPath(memberDisplays = false, joinerConfirmsFirst = false)

    @Test
    fun `the joiner may confirm before the member approves`() {
        happyPath(memberDisplays = true, joinerConfirmsFirst = true)
        happyPath(memberDisplays = false, joinerConfirmsFirst = true)
    }

    @Test
    fun `the joiner takes no bundle before its user confirms`() {
        listOf(true, false).forEach { memberDisplays ->
            val pairing = EnrollmentHarness(memberDisplays)
            pairing.showAndScan()
            pairing.handshake()
            pairing.deliver()
            assertNotNull(pairing.joiner.find<EnrollmentEffect.ShowSas>())
            // A member sends the bundle without waiting for the joiner's user.
            pairing.sendBundle(pairing.bundle()) { it }

            assertNull(pairing.joiner.find<EnrollmentEffect.PersistBundle>())
            assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.PROTOCOL_ERROR), pairing.joiner.effects.last())
        }
    }

    @Test
    fun `a displayer that reveals another nonce than it committed to is refused`() {
        listOf(true, false).forEach { memberDisplays ->
            val pairing = EnrollmentHarness(memberDisplays)
            pairing.showAndScan()
            pairing.handshake()
            pairing.deliver(drop = { FrameCodec.decode(it).type == FrameType.ENROLL_REVEAL })
            assertNull(pairing.scanner.find<EnrollmentEffect.ShowSas>())
            val reveal = """{"nonce":"${P2pEncoding.base64Url(ByteArray(32) { 3 })}"}"""
            FrameCodec.split(FrameType.ENROLL_REVEAL, msgId = 1_000_000, payload = reveal.encodeToByteArray())
                .forEach { pairing.scanner.on(EnrollmentEvent.Frame(FrameCodec.encode(it))) }

            assertNull(pairing.scanner.find<EnrollmentEffect.ShowSas>())
            assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.MISMATCH), pairing.scanner.effects.last())
        }
    }

    @Test
    fun `the scanner sends no nonce before it holds the commitment`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.handshake()
        assertEquals(emptyList(), pairing.scanner.sentTypes())
        assertEquals(listOf(FrameType.ENROLL_HELLO), pairing.displayer.sentTypes())
    }

    @Test
    fun `the code carries the app scope`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        assertEquals(SyncScope.APP, pairing.showAndScan().scope)
    }

    @Test
    fun `the devices speak the lower version both support`() {
        val newer: (EnrollmentConfig) -> EnrollmentConfig = { it.copy(version = 3, minVersion = 1) }
        val current: (EnrollmentConfig) -> EnrollmentConfig = { it }
        listOf(true, false).forEach { memberDisplays ->
            listOf(newer to current, current to newer).forEach { (memberConfig, joinerConfig) ->
                val pairing = EnrollmentHarness(
                    memberDisplays = memberDisplays,
                    memberConfig = memberConfig,
                    joinerConfig = joinerConfig,
                )
                pairing.showAndScan()
                pairing.handshake()
                pairing.deliver()

                assertEquals(1, pairing.member.machine.version)
                assertEquals(1, pairing.joiner.machine.version)
                assertNotNull(pairing.member.find<EnrollmentEffect.ShowSas>())
                assertNotNull(pairing.joiner.find<EnrollmentEffect.ShowSas>())
            }
        }
    }

    @Test
    fun `devices without a common version say which one needs an update`() {
        listOf(true, false).forEach { memberDisplays ->
            // The member speaks only a newer version: the joiner needs an update.
            val newerMember = EnrollmentHarness(memberDisplays, memberConfig = { it.copy(version = 2, minVersion = 2) })
            newerMember.showAndScan()
            newerMember.handshake()
            newerMember.deliver()
            assertEquals(peerUpdateRequired, newerMember.member.effects.last())
            assertEquals(updateRequired, newerMember.joiner.effects.last())
            assertNull(newerMember.member.machine.version)
            assertNull(newerMember.member.find<EnrollmentEffect.ShowSas>())
            assertNull(newerMember.joiner.find<EnrollmentEffect.ShowSas>())

            // The joiner speaks only a newer version: the member needs an update.
            val newerJoiner = EnrollmentHarness(memberDisplays, joinerConfig = { it.copy(version = 4, minVersion = 3) })
            newerJoiner.showAndScan()
            newerJoiner.handshake()
            newerJoiner.deliver()
            assertEquals(updateRequired, newerJoiner.member.effects.last())
            assertEquals(peerUpdateRequired, newerJoiner.joiner.effects.last())
        }
    }

    private val updateRequired = EnrollmentEffect.Failed(EnrollmentFailure.UPDATE_REQUIRED)

    private val peerUpdateRequired = EnrollmentEffect.Failed(EnrollmentFailure.PEER_UPDATE_REQUIRED)

    @Test
    fun `a HELLO of a version this build can not read still tells who needs an update`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.handshake()
        // A newer displayer whose HELLO holds nothing this build knows but its versions.
        pairing.deliver(drop = { FrameCodec.decode(it).type == FrameType.ENROLL_HELLO })
        val hello = """{"v":7,"min":5,"future":true}"""
        FrameCodec.split(FrameType.ENROLL_HELLO, msgId = 1_000_000, payload = hello.encodeToByteArray())
            .forEach { pairing.joiner.on(EnrollmentEvent.Frame(FrameCodec.encode(it))) }
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.UPDATE_REQUIRED), pairing.joiner.effects.last())

        // Versions that make no sense are a protocol error.
        val broken = EnrollmentHarness(memberDisplays = true)
        broken.showAndScan()
        broken.handshake()
        broken.deliver(drop = { FrameCodec.decode(it).type == FrameType.ENROLL_HELLO })
        FrameCodec.split(FrameType.ENROLL_HELLO, msgId = 1_000_000, payload = """{"v":1,"min":2}""".encodeToByteArray())
            .forEach { broken.joiner.on(EnrollmentEvent.Frame(FrameCodec.encode(it))) }
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.PROTOCOL_ERROR), broken.joiner.effects.last())
    }

    @Test
    fun `the member stores nothing when the joiner rejects after the approval`() {
        listOf(true, false).forEach { memberDisplays ->
            val pairing = EnrollmentHarness(memberDisplays)
            pairing.showAndScan()
            pairing.handshake()
            pairing.deliver()
            pairing.member.on(EnrollmentEvent.Approved(pairing.bundle()))
            pairing.deliver()
            assertTrue(pairing.member.machine.isFinished.not())
            assertTrue(pairing.member.machine.awaitsApproval.not())

            pairing.joiner.on(EnrollmentEvent.Rejected)
            pairing.deliver()
            assertNull(pairing.member.find<EnrollmentEffect.SaveAdmission>())
            assertTrue(FrameType.ENROLL_BUNDLE !in pairing.member.sentTypes())
            assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.REJECTED_BY_PEER), pairing.member.effects.last())
        }
    }

    @Test
    fun `the member stores nothing when the joiner cancels before the bundle arrives`() {
        listOf(true, false).forEach { memberDisplays ->
            val pairing = EnrollmentHarness(memberDisplays)
            pairing.showAndScan()
            pairing.handshake()
            pairing.deliver()
            pairing.joiner.on(EnrollmentEvent.Confirmed)
            pairing.deliver()
            pairing.member.on(EnrollmentEvent.Approved(pairing.bundle()))
            // The bundle is on its way when the joiner's user cancels.
            assertTrue(FrameType.ENROLL_BUNDLE in pairing.member.sentTypes())
            pairing.joiner.on(EnrollmentEvent.Rejected)
            pairing.deliver()

            assertNull(pairing.joiner.find<EnrollmentEffect.PersistBundle>())
            assertNull(pairing.member.find<EnrollmentEffect.SaveAdmission>())
            assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.REJECTED_BY_PEER), pairing.member.effects.last())
        }
    }

    /** Finishes the handshake; the joiner announces the record [announce] returns for a group. */
    private fun EnrollmentHarness.handshake(announce: (String) -> RosterDevice) {
        member.on(EnrollmentEvent.HandshakeDone(joiner.keys.noisePublicBytes, ByteArray(32) { 1 }))
        joiner.on(EnrollmentEvent.HandshakeDone(member.keys.noisePublicBytes, ByteArray(32) { 1 }, announce))
    }

    @Test
    fun `a joiner announces the record it signed for the group the member names`() {
        listOf(true, false).forEach { memberDisplays ->
            val pairing = EnrollmentHarness(memberDisplays)
            pairing.showAndScan()
            val completed = pairing.joiner.config.device.copy(label = "Completed")
            val groups = mutableListOf<String>()
            pairing.handshake { group -> completed.also { groups += group } }
            pairing.deliver()

            assertEquals(listOf(pairing.group.groupId), groups)
            assertEquals(completed, assertNotNull(pairing.member.find<EnrollmentEffect.ShowSas>()).peer)
        }
    }

    @Test
    fun `a completed record of another device is refused`() {
        listOf(true, false).forEach { memberDisplays ->
            val pairing = EnrollmentHarness(memberDisplays)
            pairing.showAndScan()
            val other = pairing.joiner.config.device.copy(id = "another-device")
            pairing.handshake { other }
            pairing.deliver()

            assertNull(pairing.member.find<EnrollmentEffect.ShowSas>())
            assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.PROTOCOL_ERROR), pairing.joiner.effects.last())
        }
    }

    @Test
    fun `a joiner can not announce its record without the group`() {
        val pairing = EnrollmentHarness(memberDisplays = true, memberNamesGroup = false)
        pairing.showAndScan()
        pairing.handshake { pairing.joiner.config.device }
        pairing.deliver()

        assertNull(pairing.joiner.find<EnrollmentEffect.ShowSas>())
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.PROTOCOL_ERROR), pairing.joiner.effects.last())
    }

    @Test
    fun `a displaying joiner that reveals no record is refused`() {
        val pairing = EnrollmentHarness(memberDisplays = false)
        pairing.showAndScan()
        pairing.handshake()
        pairing.deliver(drop = { FrameCodec.decode(it).type == FrameType.ENROLL_REVEAL })
        val reveal = assertNotNull(pairing.joiner.sentReveal())
        val payload = """{"nonce":"${reveal.nonce}"}"""
        FrameCodec.split(FrameType.ENROLL_REVEAL, msgId = 1_000_000, payload = payload.encodeToByteArray())
            .forEach { pairing.member.on(EnrollmentEvent.Frame(FrameCodec.encode(it))) }

        assertNull(pairing.member.find<EnrollmentEffect.ShowSas>())
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.PROTOCOL_ERROR), pairing.member.effects.last())
    }

    @Test
    fun `different digits end the enrollment before the bundle`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.handshake(displayerHash = ByteArray(32) { 1 }, scannerHash = ByteArray(32) { 2 })
        pairing.deliver()
        val memberSas = assertNotNull(pairing.member.find<EnrollmentEffect.ShowSas>()).digits
        val joinerSas = assertNotNull(pairing.joiner.find<EnrollmentEffect.ShowSas>()).digits
        assertNotEquals(memberSas, joinerSas)

        // The user sees different digits and says no.
        pairing.member.on(EnrollmentEvent.Rejected)
        pairing.deliver()
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.REJECTED), pairing.member.effects.last())
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.REJECTED_BY_PEER), pairing.joiner.effects.last())
        assertTrue(FrameType.ENROLL_BUNDLE !in pairing.member.sentTypes())
        // A late approval sends nothing.
        pairing.member.on(EnrollmentEvent.Approved(pairing.bundle()))
        assertTrue(FrameType.ENROLL_BUNDLE !in pairing.member.sentTypes())
    }

    @Test
    fun `an approval before the digits are shown is ignored`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.member.on(EnrollmentEvent.Approved(pairing.bundle()))
        pairing.handshake()
        assertTrue(FrameType.ENROLL_BUNDLE !in pairing.member.sentTypes())
    }

    @Test
    fun `a code expires after three minutes`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.displayer.handle(pairing.displayer.machine.start(pairing.now))
        val code = assertNotNull(pairing.displayer.find<EnrollmentEffect.ShowCode>()).code
        assertEquals(pairing.now + EnrollmentTimings.INVITE_TTL_MS, code.expiresAtMillis)
        pairing.displayer.on(EnrollmentEvent.Tick(code.expiresAtMillis - 1))
        assertNull(pairing.displayer.find<EnrollmentEffect.Failed>())
        pairing.displayer.on(EnrollmentEvent.Tick(code.expiresAtMillis))
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.EXPIRED), pairing.displayer.effects.last())

        // The scanner refuses a code expired by more than the clocks may differ.
        val late = code.expiresAtMillis + EnrollmentTimings.CLOCK_SKEW_MS
        pairing.scanner.handle(pairing.scanner.machine.start(late))
        pairing.scanner.on(EnrollmentEvent.CodeScanned(code, late))
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.EXPIRED), pairing.scanner.effects.last())
    }

    @Test
    fun `a scanner whose clock runs ahead of the displayer still pairs`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.displayer.handle(pairing.displayer.machine.start(pairing.now))
        val code = assertNotNull(pairing.displayer.find<EnrollmentEffect.ShowCode>()).code
        // By the clock of the scanner, the code expired half a minute ago.
        val scannerNow = code.expiresAtMillis + 30_000L
        pairing.scanner.handle(pairing.scanner.machine.start(scannerNow))
        pairing.scanner.on(EnrollmentEvent.CodeScanned(code, scannerNow))
        assertEquals(EnrollmentEffect.Connect(code), pairing.scanner.find<EnrollmentEffect.Connect>())
        // The handshake gets all of its time.
        pairing.scanner.on(EnrollmentEvent.Tick(scannerNow + EnrollmentTimings.HANDSHAKE_TIMEOUT_MS - 1))
        assertNull(pairing.scanner.find<EnrollmentEffect.Failed>())
        pairing.handshake()
        pairing.deliver()
        pairing.joiner.on(EnrollmentEvent.Confirmed)
        pairing.member.on(EnrollmentEvent.Approved(pairing.bundle()))
        pairing.deliver()
        assertEquals(EnrollmentEffect.Done, pairing.joiner.effects.last())
    }

    @Test
    fun `a scanner that never gets through an expired code reports it expired`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.displayer.handle(pairing.displayer.machine.start(pairing.now))
        val code = assertNotNull(pairing.displayer.find<EnrollmentEffect.ShowCode>()).code
        val scannedAt = code.expiresAtMillis - 1_000L
        pairing.scanner.handle(pairing.scanner.machine.start(scannedAt))
        pairing.scanner.on(EnrollmentEvent.CodeScanned(code, scannedAt))
        pairing.scanner.on(EnrollmentEvent.Tick(scannedAt + EnrollmentTimings.HANDSHAKE_TIMEOUT_MS))
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.EXPIRED), pairing.scanner.effects.last())
    }

    @Test
    fun `a code is single use and burnt on failure`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.handshake()
        val before = pairing.displayer.effects.size
        // A second device finishing a handshake with the same code gets nothing.
        pairing.displayer.on(EnrollmentEvent.HandshakeDone(ByteArray(32) { 9 }, ByteArray(32) { 9 }))
        assertEquals(before, pairing.displayer.effects.size)

        pairing.displayer.on(EnrollmentEvent.Rejected)
        val failed = pairing.displayer.effects.size
        pairing.displayer.on(EnrollmentEvent.Approved(pairing.bundle()))
        pairing.displayer.on(EnrollmentEvent.Tick(pairing.now + EnrollmentTimings.INVITE_TTL_MS))
        assertEquals(failed, pairing.displayer.effects.size)
    }

    @Test
    fun `the scanner pins the key in the code`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.scanner.on(EnrollmentEvent.HandshakeDone(ByteArray(32) { 6 }, ByteArray(32) { 1 }))
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.MISMATCH), pairing.scanner.effects.last())
        assertEquals(listOf(FrameType.ENROLL_REJECT), pairing.scanner.sentTypes())
    }

    @Test
    fun `two devices of the same role are refused`() {
        val pairing = EnrollmentHarness(memberDisplays = false)
        pairing.joiner.handle(pairing.joiner.machine.start(pairing.now))
        val code = assertNotNull(pairing.joiner.find<EnrollmentEffect.ShowCode>()).code
        // Another joiner scans the code of a joiner.
        val other = EnrollmentStateMachine(pairing.joiner.config.copy(display = DisplayMode.SCANNER), pairing.crypto)
        other.start(pairing.now)
        assertEquals(
            listOf(EnrollmentEffect.Failed(EnrollmentFailure.ROLE_MISMATCH)),
            other.on(EnrollmentEvent.CodeScanned(code, pairing.now)),
        )
    }

    @Test
    fun `the joiner can say no`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.handshake()
        pairing.deliver()
        pairing.joiner.on(EnrollmentEvent.Rejected)
        pairing.deliver()
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.REJECTED_BY_PEER), pairing.member.effects.last())
    }

    @Test
    fun `a bundle too large to hand over fails both devices with a typed reason`() {
        val pairing = EnrollmentHarness(memberDisplays = true, maxBundleSize = 1_000)
        pairing.showAndScan()
        pairing.handshake()
        pairing.deliver()
        pairing.member.on(EnrollmentEvent.Approved(pairing.bundle()))
        pairing.deliver()
        assertTrue(FrameType.ENROLL_BUNDLE !in pairing.member.sentTypes())
        assertNull(pairing.joiner.find<EnrollmentEffect.PersistBundle>())
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.TOO_LARGE), pairing.member.effects.last())
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.TOO_LARGE), pairing.joiner.effects.last())
    }

    @Test
    fun `a bundle with entries of kinds this build does not know is taken with them`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.handshake()
        pairing.deliver()
        pairing.joiner.on(EnrollmentEvent.Confirmed)
        pairing.deliver()
        // A newer member syncs a kind this build does not know.
        val bundle = pairing.bundle()
        val unknown = buildJsonObject {
            put("id", "00000000-0000-4000-8000-000000000001")
            put("kind", "card-reader")
        }
        pairing.sendBundle(bundle) { json -> JsonObject(json + ("entries" to JsonArray(listOf(unknown)))) }

        // The joiner keeps and relays them, as its peers do.
        val kept = SyncEntry(id = "00000000-0000-4000-8000-000000000001", kind = "card-reader")
        val expected = bundle.copy(entries = listOf(kept))
        assertEquals(expected, assertNotNull(pairing.joiner.find<EnrollmentEffect.PersistBundle>()).bundle)
        assertEquals(EnrollmentEffect.Done, pairing.joiner.effects.last())
    }

    @Test
    fun `a bundle holds the group its key the roster once and the entries`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        val json = P2pJson.encodeToJsonElement(EnrollmentBundle.serializer(), pairing.bundle()).jsonObject
        assertEquals(setOf("group", "groupKey", "roster", "entries"), json.keys)
    }

    @Test
    fun `a bundle without entries or with a number of no canonical form is refused`() {
        listOf<(JsonObject) -> JsonObject>(
            { json -> JsonObject(json - "entries") },
            { json -> JsonObject(json + ("future" to JsonPrimitive(1.5))) },
        ).forEach { patch ->
            val pairing = EnrollmentHarness(memberDisplays = true)
            pairing.showAndScan()
            pairing.handshake()
            pairing.deliver()
            pairing.joiner.on(EnrollmentEvent.Confirmed)
            pairing.deliver()
            pairing.sendBundle(pairing.bundle(), patch)

            assertNull(pairing.joiner.find<EnrollmentEffect.PersistBundle>())
            assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.PROTOCOL_ERROR), pairing.joiner.effects.last())
        }
    }

    @Test
    fun `a bundle that admits other keys under the id of the joiner never leaves the member`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.handshake()
        pairing.deliver()
        val group = pairing.group
        val other = group.newKeys(Random(8))
        val joinerId = pairing.joiner.keys.id
        val impostor = group.admit(
            device = other.withId(joinerId),
            by = group.founder,
            at = pairing.joiner.config.device.addedAt,
        )
        val bundle = pairing.bundle().copy(roster = group.roster.copy(devices = group.roster.devices + impostor))
        pairing.member.on(EnrollmentEvent.Approved(bundle))
        pairing.deliver()

        assertTrue(FrameType.ENROLL_BUNDLE !in pairing.member.sentTypes())
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.PROTOCOL_ERROR), pairing.member.effects.last())
    }

    @Test
    fun `waits are bounded`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.handshake()
        pairing.deliver()
        // Nobody approves for two minutes.
        pairing.member.on(EnrollmentEvent.Tick(pairing.now + EnrollmentTimings.APPROVAL_TIMEOUT_MS))
        pairing.deliver()
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.TIMEOUT), pairing.member.effects.last())
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.TIMEOUT), pairing.joiner.effects.last())
    }

    @Test
    fun `the member gives up if the joiner never acknowledges`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.handshake()
        pairing.deliver()
        pairing.joiner.on(EnrollmentEvent.Confirmed)
        pairing.member.on(EnrollmentEvent.Approved(pairing.bundle()))
        pairing.deliver(drop = { FrameCodec.decode(it).type == FrameType.ENROLL_ACK })
        assertNull(pairing.member.find<EnrollmentEffect.Failed>())
        val timings = EnrollmentTimings()
        pairing.member.on(EnrollmentEvent.Tick(pairing.now + timings.bundleTimeoutMs + timings.ackTimeoutMs))
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.TIMEOUT), pairing.member.effects.last())
        // The joiner stored the bundle, but the member never stores the admission.
        assertNotNull(pairing.joiner.find<EnrollmentEffect.PersistBundle>())
        assertNull(pairing.member.find<EnrollmentEffect.SaveAdmission>())
    }

    @Test
    fun `a handshake that never finishes times out`() {
        val pairing = EnrollmentHarness(memberDisplays = true)
        pairing.showAndScan()
        pairing.scanner.on(EnrollmentEvent.Tick(pairing.now + EnrollmentTimings.HANDSHAKE_TIMEOUT_MS))
        assertEquals(EnrollmentEffect.Failed(EnrollmentFailure.TIMEOUT), pairing.scanner.effects.last())
        // Nothing was connected, so nothing is sent.
        assertEquals(emptyList(), pairing.scanner.sentTypes())
    }
}
