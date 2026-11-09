package com.artemchep.keyguard.provider.keyguard.enroll

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.enroll.DisplayMode
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentRole
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentTimings
import com.artemchep.keyguard.feature.p2psync.model.PairingCode
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.Transport
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.provider.keyguard.KeyguardPasswordPrompt
import com.artemchep.keyguard.provider.keyguard.applink.TEST_MASTER_PASSWORD
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkTestDevice
import com.artemchep.keyguard.provider.keyguard.session.realTime
import com.artemchep.keyguard.provider.keyguard.session.testRoomConnector
import com.artemchep.keyguard.provider.keyguard.signPrivateKey
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import com.artemchep.keyguard.provider.keyguard.toRosterDevice
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayConnector
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardTransportContext
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardTransportFactory
import com.artemchep.keyguard.provider.keyguard.transport.NeverOpeningTransport
import com.artemchep.keyguard.provider.keyguard.transport.RelayEndpoint
import com.artemchep.keyguard.provider.keyguard.transport.TestRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Two apps pair through an in-memory relay with relay forwarding and real Noise
 * handshakes: a member that links its app, which has a forwarder, and a joiner with an
 * empty database.
 */
// One function per scenario, plus the wiring of the two devices.
@Suppress("TooManyFunctions")
class EnrollmentCoordinatorTest {
    private val relay = TestRelay()

    @Test
    fun `a linked member shows the code and the joiner scans it`() = realTime {
        withPair { pair ->
            pair.linkMember()
            coroutineScope {
                val (adding, joining) = pair.untilVerifyMemberShows(this)
                verifyAndApprove(adding, joining)
                assertJoined(pair, adding, joining)
            }
        }
    }

    @Test
    fun `a pairing whose WebRTC does not open goes over the relay`() = realTime {
        val webRtc = NeverOpeningTransport()
        val transports = KeyguardTransportFactory { context -> listOf(webRtc, context.relayForward) }
        withPair(memberTransports = transports, joinerTransports = transports) { pair ->
            coroutineScope {
                val (adding, joining) = pair.untilVerify(this)
                verifyAndApprove(adding, joining)
                assertJoined(pair, adding, joining)
            }
        }
        // Both raced it; neither waited for it to give up.
        assertTrue(webRtc.attempts.get() >= 2)
    }

    @Test
    fun `of two apps that are not linked, the one that scans the code founds the link`() = realTime {
        withPair { pair ->
            coroutineScope {
                val (adding, joining) = pair.untilVerify(this)
                verifyAndApprove(adding, joining)
                assertJoined(pair, adding, joining)
            }
        }
    }

    @Test
    fun `a linked app keeps its link, whichever device shows the code`() = realTime {
        val laptop = device("Laptop", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS)
        val phone = device("Phone", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS)
        val tablet = device("Tablet", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS)
        val watch = device("Watch", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS)
        coroutineScope {
            assertEquals(EnrollmentRole.MEMBER to EnrollmentRole.JOINER, pairApps(scanner = laptop, shower = phone))
            val groupId = laptop.membership().groupId
            // A linked app that scans the code admits the other one.
            assertEquals(EnrollmentRole.MEMBER to EnrollmentRole.JOINER, pairApps(scanner = phone, shower = tablet))
            // A linked app that shows the code admits the other one.
            assertEquals(EnrollmentRole.JOINER to EnrollmentRole.MEMBER, pairApps(scanner = watch, shower = tablet))
            listOf(phone, tablet, watch).forEach { device ->
                assertEquals(groupId, device.membership().groupId)
            }
            // The last member that admitted a device knows them all.
            assertEquals(4, RosterActivity(tablet.roster(), tablet.crypto::sha256).activeIds.size)
        }
    }

    @Test
    fun `two apps linked to each other are told so`() = realTime {
        val laptop = device("Laptop", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS)
        val phone = device("Phone", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS)
        coroutineScope {
            pairApps(scanner = laptop, shower = phone)
            assertPairingFails(scanner = phone, shower = laptop, KeyguardPairingError.LINKED_TOGETHER)
        }
    }

    @Test
    fun `two apps linked to other devices can not pair`() = realTime {
        val laptop = device("Laptop", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS)
        val phone = device("Phone", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS)
        val tablet = device("Tablet", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS)
        val watch = device("Watch", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS)
        coroutineScope {
            pairApps(scanner = laptop, shower = phone)
            pairApps(scanner = tablet, shower = watch)
            val laptopGroup = laptop.membership().groupId
            val tabletGroup = tablet.membership().groupId
            assertPairingFails(scanner = tablet, shower = laptop, KeyguardPairingError.ALREADY_LINKED)
            // Both links stay as they were.
            assertEquals(laptopGroup, laptop.membership().groupId)
            assertEquals(tabletGroup, tablet.membership().groupId)
        }
    }

    @Test
    fun `a rejection ends the pairing on both devices`() = realTime {
        withPair { pair ->
            coroutineScope {
                val (adding, joining) = pair.untilVerify(this)
                adding.reject()
                assertEquals(
                    EnrollmentStep.Failed(KeyguardPairingError.CANCELLED),
                    adding.await { it is EnrollmentStep.Failed },
                )
                assertEquals(
                    EnrollmentStep.Failed(KeyguardPairingError.REJECTED),
                    joining.await { it is EnrollmentStep.Failed },
                )
            }
            // Nothing was admitted.
            assertEquals(1, pair.member.roster().devices.size)
            assertNull(pair.joiner.service.membership())
        }
    }

    @Test
    fun `an unused code expires`() = realTime {
        withPair(inviteTtlMs = 1_500L) { pair ->
            coroutineScope {
                val showing = pair.joiner.pairApp(this)
                showing.showCode()
                assertEquals(
                    EnrollmentStep.Failed(KeyguardPairingError.EXPIRED),
                    showing.await { it is EnrollmentStep.Failed },
                )
            }
        }
    }

    @Test
    fun `a pairing without the master password is cancelled before anything is created`() = realTime {
        withPair { pair ->
            coroutineScope {
                // The scanner would found the link.
                val adding = pair.member.pairApp(this, password = { null })
                adding.scanCode()
                assertEquals(
                    EnrollmentStep.Failed(KeyguardPairingError.CANCELLED),
                    adding.await { it is EnrollmentStep.Failed },
                )
                val joining = pair.joiner.pairApp(this, password = { null })
                joining.showCode()
                assertEquals(
                    EnrollmentStep.Failed(KeyguardPairingError.CANCELLED),
                    joining.await { it is EnrollmentStep.Failed },
                )
            }
            // Neither device founded or joined a link.
            assertEquals(null, pair.member.service.membership())
            assertEquals(null, pair.joiner.service.membership())
        }
    }

    @Test
    fun `the admission covers the identity, so the new device may rename itself`() = realTime {
        val device = AppLinkTestDevice("Laptop", realTime = true)
        device.link()
        val vault = device.components.enrollmentVault
        val member = vault.member(P2pGroupId.AppLink)
        val joiner = device.newDevice(label = "Phone")
        val signKey = joiner.signPrivateKey()
        val signer = RosterSigner(device.crypto, member.membership.groupId)
        // Admitted after the member was.
        val wall = maxOf(Clock.System.now().toEpochMilliseconds(), member.record.addedAt.wall + 1)
        val addedAt = Hlc(wall = wall, counter = 0, node = member.record.id)
        val record = joiner.toRosterDevice(addedAt = addedAt, addedBy = member.record.id)

        val authority = vault.openAuthority(member, TEST_MASTER_PASSWORD)
        // A record that changed before its admission is not new.
        assertFailsWith<KeyguardAdmissionException> {
            vault.admit(member, signer.selfSign(record.copy(updatedAt = addedAt.copy(counter = 1)), signKey), authority)
        }
        // Nor is a device without an authority key admitted.
        assertFailsWith<KeyguardAdmissionException> {
            vault.admit(member, signer.selfSign(record.copy(authorityPub = ""), signKey), authority)
        }

        // Nor is a record signed for another group.
        val elsewhere = RosterSigner(device.crypto, "00000000-0000-4000-8000-000000000000")
        assertFailsWith<KeyguardAdmissionException> {
            vault.admit(member, elsewhere.selfSign(record, signKey), authority)
        }
        // Nor a name the other devices would drop.
        assertFailsWith<KeyguardAdmissionException> {
            vault.admit(member, signer.selfSign(record.copy(label = "line\nbreak"), signKey), authority)
        }

        val bundle = vault.admit(member, signer.selfSign(record, signKey), authority).bundle
        authority.close()
        val validator = RosterValidator(device.crypto, member.membership.groupId)
        val admitted = validator.validate(bundle.roster).devices.single { it.id == joiner.deviceId }
        val renamed = signer.update(admitted, addedAt.copy(counter = 1), signKey, label = "Work phone")
        val withRename = bundle.roster.copy(devices = bundle.roster.devices - admitted + renamed)
        val roster = validator.merge(bundle.roster, withRename)
        assertEquals(renamed, roster.devices.single { it.id == joiner.deviceId })
        assertTrue(RosterActivity(roster, device.crypto::sha256).isActive(joiner.deviceId))
    }

    @Test
    fun `a malformed code is refused`() = realTime {
        withPair { pair ->
            coroutineScope {
                val joining = pair.joiner.pairApp(this)
                joining.scanCode()
                joining.await { it == EnrollmentStep.Scanning }
                joining.submitCode("KGP:NOT-A-CODE")
                assertEquals(
                    EnrollmentStep.Failed(KeyguardPairingError.INVALID_CODE),
                    joining.await { it is EnrollmentStep.Failed },
                )
            }
        }
    }

    @Test
    fun `a code that names another relay never gets the access token`() = realTime {
        val opened = Collections.synchronizedList(mutableListOf<RelayEndpoint>())
        val recording = KeyguardRelayConnector { endpoint, room ->
            opened += endpoint
            relay.connector.open(endpoint, room)
        }
        val joiner = device("Phone", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS, relayConnector = recording)
        coroutineScope {
            val joining = joiner.pairApp(this, privateNetwork)
            joining.scanCode()
            joining.await { it == EnrollmentStep.Scanning }
            // A code anyone can make, naming a relay of its own.
            val crafted = PairingCode(
                psk = Random.nextBytes(PairingCode.KEY_SIZE),
                pub = Random.nextBytes(PairingCode.KEY_SIZE),
                member = true,
                expiresAt = Clock.System.now().epochSeconds + 60L,
                scope = SyncScope.APP,
                relay = "wss://evil.example",
                ice = "https://evil.example/ice",
            )
            joining.submitCode(crafted.encode())
            awaitOpened(opened)
            joining.cancel()
        }
        val endpoint = opened.single()
        assertEquals("wss://evil.example/v1/ws", endpoint.webSocketUrl)
        assertNull(endpoint.token)
        assertNull(endpoint.iceToken)
    }

    @Test
    fun `a code of the saved relay takes the access token along`() = realTime {
        val opened = Collections.synchronizedList(mutableListOf<RelayEndpoint>())
        val recording = KeyguardRelayConnector { endpoint, room ->
            opened += endpoint
            relay.connector.open(endpoint, room)
        }
        val laptop = device("Laptop", KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS, relayConnector = recording)
        coroutineScope {
            val showing = laptop.pairApp(this, privateNetwork)
            showing.showCode()
            showing.await { it is EnrollmentStep.ShowCode }
            awaitOpened(opened)
            showing.cancel()
        }
        val endpoint = opened.first()
        assertEquals("wss://relay.example.com/v1/ws", endpoint.webSocketUrl)
        assertEquals(privateNetwork.accessToken, endpoint.token)
    }

    @Test
    fun `a pairing whose peer stops reading can still be cancelled`() = realTime {
        val stalled = MutableStateFlow(false)
        withPair(memberTransports = StallingTransports(stalled)) { pair ->
            coroutineScope {
                val (adding, joining) = pair.untilVerify(this)
                // The joiner stops reading: the bundle can not leave the member.
                stalled.value = true
                joining.confirm()
                adding.confirm()
                adding.await { it == EnrollmentStep.Transferring }
                adding.cancel()
                assertEquals(
                    EnrollmentStep.Failed(KeyguardPairingError.CANCELLED),
                    withTimeoutOrNull(5.seconds) { adding.step.first { it is EnrollmentStep.Failed } },
                )
                joining.cancel()
            }
            assertNull(pair.joiner.service.membership())
        }
    }

    @Test
    fun `a pairing whose peer stops reading fails at its deadline`() = realTime {
        val stalled = MutableStateFlow(false)
        val timings = KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS
            .copy(bundleTimeoutMs = 1_000L, ackTimeoutMs = 1_000L)
        withPair(timings = timings, memberTransports = StallingTransports(stalled)) { pair ->
            coroutineScope {
                val (adding, joining) = pair.untilVerify(this)
                // The joiner confirms; its confirmation reaches the member before the stall.
                joining.confirm()
                delay(1.seconds)
                stalled.value = true
                adding.confirm()
                // Nothing cancels it: the deadline of the acknowledgement ends it, well before
                // the stuck write would time out.
                assertEquals(
                    EnrollmentStep.Failed(KeyguardPairingError.NETWORK),
                    withTimeoutOrNull(10.seconds) { adding.step.first { it is EnrollmentStep.Failed } },
                )
                joining.cancel()
            }
        }
    }

    @Test
    fun `the member stores the admission only once both users confirmed`() = realTime {
        withPair { pair ->
            coroutineScope {
                val (adding, joining) = pair.untilVerify(this)
                // The member's user confirms first.
                adding.confirm()
                adding.await { it is EnrollmentStep.Verify && it.confirmed }
                delay(500L)
                assertEquals(1, pair.member.roster().devices.size)
                assertIs<EnrollmentStep.Verify>(adding.step.value)

                joining.confirm()
                assertJoined(pair, adding, joining)
            }
        }
    }

    @Test
    fun `a joiner that cancels before the bundle arrives leaves the roster unchanged`() = realTime {
        val stalled = MutableStateFlow(false)
        withPair(memberTransports = StallingTransports(stalled)) { pair ->
            coroutineScope {
                val (adding, joining) = pair.untilVerify(this)
                // The joiner confirms; its confirmation reaches the member before the stall.
                joining.confirm()
                delay(1.seconds)
                stalled.value = true
                // The bundle can not leave the member when the joiner's user cancels.
                adding.confirm()
                adding.await { it == EnrollmentStep.Transferring }
                joining.cancel()
                assertEquals(
                    EnrollmentStep.Failed(KeyguardPairingError.REJECTED),
                    adding.await { it is EnrollmentStep.Failed },
                )
                stalled.value = false
            }
            assertEquals(1, pair.member.roster().devices.size)
            assertNull(pair.joiner.service.membership())
        }
    }

    @Test
    fun `a member adopts a joiner whose acknowledgement it missed once the roster of the joiner reaches it`() =
        realTime {
            val member = AppLinkTestDevice("Laptop", realTime = true)
            member.link()
            val joiner = AppLinkTestDevice("Phone", realTime = true)
            val device = joiner.newDevice()
            // The joiner stored the bundle; its acknowledgement never reached the member.
            val bundle = member.admit(device, save = false)
            joiner.service.import(bundle, device, P2pNetwork())
            assertEquals(1, member.roster().devices.size)

            // The roster of the joiner holds its admission, which the member signed.
            member.receive(from = joiner)
            assertTrue(RosterActivity(member.roster(), member.crypto::sha256).isActive(device.deviceId))
        }

    @Test
    fun `a rejection after the member's approval leaves the roster unchanged`() = realTime {
        listOf(true, false).forEach { memberShows ->
            withPair { pair ->
                // A member shows the code only once it is linked; else the scanner founds the link.
                if (memberShows) pair.linkMember()
                val devices = if (memberShows) 2 else 1
                coroutineScope {
                    val (adding, joining) = if (memberShows) {
                        pair.untilVerifyMemberShows(this)
                    } else {
                        pair.untilVerify(this)
                    }
                    adding.confirm()
                    adding.await { it is EnrollmentStep.Verify && it.confirmed }

                    // The joiner's user says no, or cancels.
                    joining.reject()
                    assertEquals(
                        EnrollmentStep.Failed(KeyguardPairingError.REJECTED),
                        adding.await { it is EnrollmentStep.Failed },
                    )
                }
                assertEquals(devices, pair.member.roster().devices.size)
                assertNull(pair.joiner.service.membership())
            }
        }
    }

    @Test
    fun `the displayer confirms only with the right digits, which it may type again`() = realTime {
        withPair { pair ->
            coroutineScope {
                val (adding, joining) = pair.untilVerify(this)
                val verify = assertIs<EnrollmentStep.Verify>(joining.step.value)
                assertEquals(DisplayMode.DISPLAYER, verify.display)
                // A tap is not enough on the device that showed the code.
                joining.approve()
                joining.enterDigits(wrongDigits(verify.sas))
                val missed = joining.await { it is EnrollmentStep.Verify && it.misses == 1 }
                assertIs<EnrollmentStep.Verify>(missed)
                assertEquals(false, missed.confirmed)
                joining.enterDigits(wrongDigits(verify.sas))
                joining.await { it is EnrollmentStep.Verify && it.misses == 2 }

                // The scanner shows the digits; spaces do not count.
                val shown = assertIs<EnrollmentStep.Verify>(adding.step.value)
                assertEquals(DisplayMode.SCANNER, shown.display)
                joining.enterDigits(shown.sas.take(3) + " " + shown.sas.drop(3))
                joining.await { it is EnrollmentStep.Verify && it.confirmed }
                // The scanner's user types nothing.
                adding.enterDigits(shown.sas)
                delay(500L)
                assertEquals(false, assertIs<EnrollmentStep.Verify>(adding.step.value).confirmed)
                adding.approve()
                assertJoined(pair, adding, joining)
            }
        }
    }

    @Test
    fun `an effect that waited behind a stuck write does not revive a finished pairing`() = realTime {
        val open = MutableStateFlow(false)
        val timings = KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS.copy(approvalTimeoutMs = 1_500L)
        // The joiner shows the code. It writes the handshake and its HELLO; its reveal, and the
        // digits queued behind it, wait.
        withPair(timings = timings, joinerTransports = GatedTransports(openWrites = 2, open)) { pair ->
            coroutineScope {
                val (joining, adding) = untilScanned(this, shower = pair.joiner, scanner = pair.member)

                // Nobody approves in time.
                val failed = EnrollmentStep.Failed(KeyguardPairingError.NETWORK)
                assertEquals(failed, joining.await { it is EnrollmentStep.Failed })
                // The reveal leaves, and the digits behind it are handled.
                open.value = true
                delay(1_000L)
                assertEquals(failed, joining.step.value)
                adding.cancel()
            }
            assertEquals(1, pair.member.roster().devices.size)
        }
    }

    @Test
    fun `a finished pairing takes no late commands`() = realTime {
        withPair { pair ->
            coroutineScope {
                val (adding, joining) = pair.untilVerify(this)
                val sas = assertIs<EnrollmentStep.Verify>(joining.step.value).sas
                joining.reject()
                val failed = adding.await { it is EnrollmentStep.Failed }
                adding.enterDigits(sas)
                adding.approve()
                delay(500L)
                assertEquals(failed, adding.step.value)
            }
            assertEquals(1, pair.member.roster().devices.size)
        }
    }

    /**
     * Pairs the apps of the [scanner] and the [shower], which shows the code, until both
     * are done, each confirming the digits; returns the roles of the scanner and the shower.
     */
    private suspend fun CoroutineScope.pairApps(
        scanner: AppLinkTestDevice,
        shower: AppLinkTestDevice,
    ): Pair<EnrollmentRole, EnrollmentRole> {
        val (showing, scanning) = untilScanned(this, shower, scanner)
        val scannerRole = assertIs<EnrollmentStep.Verify>(scanning.await { it is EnrollmentStep.Verify }).role
        val showerRole = assertIs<EnrollmentStep.Verify>(showing.await { it is EnrollmentStep.Verify }).role
        scanning.confirm()
        showing.confirm()
        listOf(scanning, showing).forEach { enrollment ->
            assertEquals(
                EnrollmentStep.Done(P2pGroupId.AppLink),
                enrollment.await { it is EnrollmentStep.Done || it is EnrollmentStep.Failed },
            )
        }
        return scannerRole to showerRole
    }

    /** The [scanner] turns down the code of the [shower] for the [error], before they connect. */
    private suspend fun CoroutineScope.assertPairingFails(
        scanner: AppLinkTestDevice,
        shower: AppLinkTestDevice,
        error: KeyguardPairingError,
    ) {
        val (showing, scanning) = untilScanned(this, shower, scanner)
        assertEquals(EnrollmentStep.Failed(error), scanning.await { it is EnrollmentStep.Failed })
        showing.cancel()
        showing.await { it is EnrollmentStep.Failed }
    }

    /**
     * The [shower] shows a code and the [scanner] enters it, each pairing its app, see
     * [KeyguardEnrollmentCoordinator.pairApp]; returns the enrollments of both.
     */
    private suspend fun untilScanned(
        scope: CoroutineScope,
        shower: AppLinkTestDevice,
        scanner: AppLinkTestDevice,
    ): Pair<KeyguardEnrollment, KeyguardEnrollment> {
        val showing = shower.pairApp(scope)
        showing.showCode()
        val code = assertIs<EnrollmentStep.ShowCode>(showing.await { it is EnrollmentStep.ShowCode }).code
        val scanning = scanner.pairApp(scope)
        scanning.scanCode()
        scanning.await { it == EnrollmentStep.Scanning }
        scanning.submitCode(code)
        return showing to scanning
    }

    private fun AppLinkTestDevice.pairApp(
        scope: CoroutineScope,
        network: P2pNetwork = P2pNetwork(),
        password: KeyguardPasswordPrompt = { TEST_MASTER_PASSWORD },
    ): KeyguardEnrollment = components.enrollment.pairApp(name, network, password, scope)

    /** Returns other six digits than [sas]. */
    private fun wrongDigits(sas: String): String = sas.map { if (it == '9') '0' else it + 1 }.joinToString("")

    /**
     * Confirms the digits as the user of this device does: the device that showed the code
     * types the digits, the one that scanned it taps.
     */
    private suspend fun KeyguardEnrollment.confirm() {
        val verify = assertIs<EnrollmentStep.Verify>(await { it is EnrollmentStep.Verify })
        when (verify.display) {
            DisplayMode.DISPLAYER -> enterDigits(verify.sas)
            DisplayMode.SCANNER -> approve()
        }
    }

    private suspend fun awaitOpened(opened: List<RelayEndpoint>) {
        withTimeoutOrNull(TIMEOUT) {
            while (opened.isEmpty()) delay(10L)
        } ?: fail("No relay was opened")
    }

    private suspend fun verifyAndApprove(
        adding: KeyguardEnrollment,
        joining: KeyguardEnrollment,
    ) {
        val memberSas = assertIs<EnrollmentStep.Verify>(adding.await { it is EnrollmentStep.Verify })
        val joinerSas = assertIs<EnrollmentStep.Verify>(joining.await { it is EnrollmentStep.Verify })
        assertEquals(memberSas.sas, joinerSas.sas)
        assertEquals(6, memberSas.sas.length)
        assertEquals("Phone", memberSas.peerLabel)
        assertEquals(EnrollmentRole.MEMBER, memberSas.role)
        assertEquals(EnrollmentRole.JOINER, joinerSas.role)

        // The users of both devices confirm.
        joining.confirm()
        adding.confirm()
        assertEquals(
            EnrollmentStep.Done(P2pGroupId.AppLink),
            adding.await { it is EnrollmentStep.Done || it is EnrollmentStep.Failed },
        )
    }

    private suspend fun assertJoined(
        pair: Pairing,
        adding: KeyguardEnrollment,
        joining: KeyguardEnrollment,
    ) {
        assertEquals(
            EnrollmentStep.Done(P2pGroupId.AppLink),
            joining.await { it is EnrollmentStep.Done || it is EnrollmentStep.Failed },
        )
        // The member stores the admission once the joiner acknowledged the bundle.
        assertEquals(
            EnrollmentStep.Done(P2pGroupId.AppLink),
            adding.await { it is EnrollmentStep.Done || it is EnrollmentStep.Failed },
        )
        val member = pair.member.membership()
        val joined = pair.joiner.membership()
        assertEquals(member.groupId, joined.groupId)
        assertEquals(member.groupKeyBase64, joined.groupKeyBase64)
        assertEquals(member.founderId, joined.founderId)
        assertEquals("Phone", joined.device.label)
        // The joiner got the data of the link.
        assertEquals(pair.member.forwarderSyncId("Mail"), pair.joiner.forwarderSyncId("Mail"))
        assertEquals(mapOf("apiKey" to "secret"), pair.joiner.forwarder("Mail").data)

        // Both devices list the new one as active.
        assertTrue(RosterActivity(pair.member.roster(), pair.member.crypto::sha256).isActive(joined.device.deviceId))
        assertTrue(RosterActivity(pair.joiner.roster(), pair.joiner.crypto::sha256).isActive(joined.device.deviceId))
        // The member tells its other devices about the new one.
        assertEquals(1, pair.announced.get())
    }

    /** The member, which links its app, and the joiner, which joins the link. */
    private inner class Pairing(
        timings: EnrollmentTimings,
        memberTransports: KeyguardTransportFactory,
        joinerTransports: KeyguardTransportFactory,
    ) {
        val announced = AtomicInteger()

        val member = device(
            "Laptop",
            timings,
            transports = memberTransports,
            onQueueSync = { announced.incrementAndGet() },
        )

        val joiner = device("Phone", timings, transports = joinerTransports)

        /** Links the member to another device first: a linked app admits the other one also when it shows the code. */
        suspend fun linkMember() {
            member.link()
            member.admit(member.newDevice("Tablet"))
        }

        /**
         * The joiner shows the code and the member scans it, until both show the digits: of
         * two apps that are not linked, the scanner founds the link. Returns the enrollments
         * of the member and the joiner.
         */
        suspend fun untilVerify(scope: CoroutineScope): Pair<KeyguardEnrollment, KeyguardEnrollment> {
            val (joining, adding) = untilScanned(scope, shower = joiner, scanner = member)
            adding.await { it is EnrollmentStep.Verify }
            joining.await { it is EnrollmentStep.Verify }
            return adding to joining
        }

        /** The member, linked by [linkMember], shows the code and the joiner scans it, until both show the digits. */
        suspend fun untilVerifyMemberShows(scope: CoroutineScope): Pair<KeyguardEnrollment, KeyguardEnrollment> {
            val (adding, joining) = untilScanned(scope, shower = member, scanner = joiner)
            adding.await { it is EnrollmentStep.Verify }
            joining.await { it is EnrollmentStep.Verify }
            return adding to joining
        }
    }

    /** A device on the real clock: the pairing stamps the joiner's record with it. */
    private fun device(
        name: String,
        timings: EnrollmentTimings,
        relayConnector: KeyguardRelayConnector = relay.connector,
        transports: KeyguardTransportFactory = KeyguardTransportFactory.RelayOnly,
        onQueueSync: () -> Unit = {},
    ) = AppLinkTestDevice(
        name = name,
        realTime = true,
        roomConnector = testRoomConnector(relayConnector, transports),
        enrollmentTimings = timings,
        onQueueSync = onQueueSync,
    )

    private suspend fun withPair(
        inviteTtlMs: Long = EnrollmentTimings.INVITE_TTL_MS,
        timings: EnrollmentTimings = KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS,
        memberTransports: KeyguardTransportFactory = KeyguardTransportFactory.RelayOnly,
        joinerTransports: KeyguardTransportFactory = KeyguardTransportFactory.RelayOnly,
        block: suspend (Pairing) -> Unit,
    ) {
        val pair = Pairing(timings.copy(inviteTtlMs = inviteTtlMs), memberTransports, joinerTransports)
        pair.member.addForwarder("Mail", "simplelogin", "apiKey" to "secret")
        block(pair)
    }

    /** The stored roster of the link, validated. */
    private fun AppLinkTestDevice.roster(): Roster {
        val stored = KeyguardSyncStore(db, P2pGroupId.APP_LINK_ID).loadRoster(membership().founderId)
        return RosterValidator(crypto, membership().groupId).validate(stored)
    }

    private suspend fun KeyguardEnrollment.await(
        predicate: (EnrollmentStep) -> Boolean,
    ): EnrollmentStep = withTimeoutOrNull(TIMEOUT) { step.first(predicate) }
        ?: fail("The enrollment is still at ${step.value}")

    private companion object {
        val TIMEOUT = 40.seconds

        /** A private relay, and its access token. */
        val privateNetwork = P2pNetwork(relayUrl = "wss://relay.example.com", accessToken = "relay-token-0123456789")
    }
}

/** Relay forwarding that writes the first [openWrites] messages, then waits until [open]. */
private class GatedTransports(
    private val openWrites: Int,
    private val open: StateFlow<Boolean>,
) : KeyguardTransportFactory {
    override fun create(context: KeyguardTransportContext): List<Transport> {
        val inner = context.relayForward
        return listOf(
            object : Transport {
                override val kind: TransportKind get() = inner.kind

                override suspend fun connect(peerId: String, role: ConnectRole): ByteStream {
                    val stream = inner.connect(peerId, role)
                    val writes = AtomicInteger()
                    return object : ByteStream by stream {
                        override suspend fun write(bytes: ByteArray) {
                            if (writes.incrementAndGet() > openWrites) open.first { it }
                            stream.write(bytes)
                        }
                    }
                }
            },
        )
    }
}

/** Relay forwarding whose writes wait while [stalled] is on, as to a peer that stopped reading. */
private class StallingTransports(
    private val stalled: StateFlow<Boolean>,
) : KeyguardTransportFactory {
    override fun create(context: KeyguardTransportContext): List<Transport> {
        val inner = context.relayForward
        return listOf(
            object : Transport {
                override val kind: TransportKind get() = inner.kind

                override suspend fun connect(peerId: String, role: ConnectRole): ByteStream =
                    StallingStream(inner.connect(peerId, role), stalled)
            },
        )
    }
}

private class StallingStream(
    private val inner: ByteStream,
    private val stalled: StateFlow<Boolean>,
) : ByteStream by inner {
    override suspend fun awaitWritable() {
        stalled.first { !it }
        inner.awaitWritable()
    }

    override suspend fun write(bytes: ByteArray) {
        stalled.first { !it }
        inner.write(bytes)
    }
}
