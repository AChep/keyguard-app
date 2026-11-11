package com.artemchep.keyguard.integration.p2p

import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentRole
import com.artemchep.keyguard.feature.p2pwebrtc.WebRtcTransportConfig
import com.artemchep.keyguard.feature.p2pwebrtc.WebRtcTransportFactory
import com.artemchep.keyguard.provider.keyguard.enroll.EnrollmentStep
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardEnrollment
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardTransportFactory
import com.artemchep.keyguard.util.webrtc.WebRtcIceTransportPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The transports the application roots bind, set up for one machine. */
internal object Transports {
    private const val LOOPBACK = "127.0.0.1:0"

    /** What Wear OS and the AutoFill extension use. */
    fun relayOnly(): KeyguardTransportFactory = KeyguardTransportFactory.RelayOnly

    /** What the phone, desktop and Apple apps use, on loopback host candidates. */
    fun webRtc(): KeyguardTransportFactory = KeyguardTransportFactory.preferring(loopbackWebRtc())

    /** WebRTC only, for scenarios that require a direct connection even on a slow host. */
    fun webRtcOnly(): KeyguardTransportFactory = KeyguardTransportFactory { context ->
        loopbackWebRtc().create(context.session)
    }

    private fun loopbackWebRtc() = WebRtcTransportFactory(
        WebRtcTransportConfig(bindAddresses = listOf(LOOPBACK)),
    )

    /**
     * WebRTC through TURN only. Without a TURN server nothing connects, and relay
     * forwarding wins the race of the transports.
     */
    fun webRtcRelayPolicy(): KeyguardTransportFactory = KeyguardTransportFactory.preferring(turnOnlyWebRtc())

    /**
     * WebRTC through TURN only, and no relay forwarding to race it: a TURN connection
     * slower than the head start of WebRTC still carries the session.
     */
    fun turnOnly(): KeyguardTransportFactory = KeyguardTransportFactory { context ->
        turnOnlyWebRtc().create(context.session)
    }

    private fun turnOnlyWebRtc() = WebRtcTransportFactory(
        WebRtcTransportConfig(
            iceTransportPolicy = WebRtcIceTransportPolicy.RELAY,
            bindAddresses = listOf(LOOPBACK),
        ),
    )
}

/** The apps of one scenario and the relay they share. Closing it stops them all. */
internal class P2pTestbed(
    val relay: RelayServer,
) : AutoCloseable {
    private val devices = mutableListOf<P2pE2eDevice>()

    /**
     * An app that is not linked yet. A [live] one is in the foreground and runs the
     * AppLink worker: it holds the session once it is linked to another device. Without
     * one it is in the background, like Android's background sync: each sync pass
     * connects once. With [relayStorage] its user turned relay storage on.
     */
    fun device(
        name: String,
        transports: KeyguardTransportFactory,
        live: Boolean = true,
        relayStorage: Boolean = false,
    ): P2pE2eDevice = P2pE2eDevice(name, relay, transports, relayStorage)
        .also(devices::add)
        .also { if (live) it.startLive() }

    suspend fun diagnostics(): String = buildString {
        devices.forEach { append(it.diagnostics()) }
    }

    override fun close() {
        devices.asReversed().forEach { device ->
            runCatching { device.close() }
        }
    }
}

/**
 * Runs one scenario against a new relay, with [turn] servers and relay [storage] if
 * given, on the real clock. A failure prints the relay's output.
 */
internal fun p2pScenario(
    turn: RelayServer.Turn? = null,
    storage: RelayServer.Storage? = null,
    timeout: Duration = SCENARIO_TIMEOUT,
    block: suspend CoroutineScope.(P2pTestbed) -> Unit,
) {
    RelayServer.start(turn, storage).use { relay ->
        P2pTestbed(relay).use { testbed ->
            runCatching {
                runBlocking(Dispatchers.Default) {
                    withTimeout(timeout) { block(testbed) }
                }
            }
                .onFailure {
                    println(relay.diagnostics())
                    runBlocking(Dispatchers.Default) {
                        withTimeoutOrNull(DIAGNOSTICS_TIMEOUT) { println(testbed.diagnostics()) }
                    }
                }
                .getOrThrow()
        }
    }
}

/**
 * Links the app of [joiner] to the app of [member] the way two users would, see
 * [com.artemchep.keyguard.provider.keyguard.enroll.KeyguardEnrollmentCoordinator.pairApp]:
 * a linked member shows the code and the joiner enters it; else the joiner shows it, and
 * the member enters it and starts the link. The user of the device that showed the code
 * types the digits the other one shows, and the other user confirms them.
 */
internal suspend fun link(
    member: P2pE2eDevice,
    joiner: P2pE2eDevice,
) {
    val started = TimeSource.Monotonic.markNow()
    val memberShows = member.linkState() is DAppLinkState.Linked
    val (shower, scanner) = if (memberShows) member to joiner else joiner to member
    coroutineScope {
        val showing = shower.enrollment.pairApp(shower.name, shower.network, password = { MASTER_PASSWORD }, this)
        showing.showCode()
        val shown = showing.await("show a code") { it is EnrollmentStep.ShowCode }
        val code = assertIs<EnrollmentStep.ShowCode>(shown).code
        val scanning = scanner.enrollment.pairApp(scanner.name, scanner.network, password = { MASTER_PASSWORD }, this)
        scanning.scanCode()
        scanning.await("wait for a code") { it == EnrollmentStep.Scanning }
        scanning.submitCode(code)

        val showerSas = assertIs<EnrollmentStep.Verify>(showing.await("verify") { it is EnrollmentStep.Verify })
        val scannerSas = assertIs<EnrollmentStep.Verify>(scanning.await("verify") { it is EnrollmentStep.Verify })
        assertEquals(showerSas.sas, scannerSas.sas)
        val memberSas = if (memberShows) showerSas else scannerSas
        assertEquals(joiner.name, memberSas.peerLabel)
        assertEquals(EnrollmentRole.MEMBER, memberSas.role)

        // The scanner's user confirms with a tap; the shower's user types the digits the
        // scanner shows.
        scanning.approve()
        showing.enterDigits(scannerSas.sas)
        listOf(showing, scanning).forEach { enrollment ->
            val done = assertIs<EnrollmentStep.Done>(enrollment.await("finish") { it is EnrollmentStep.Done })
            assertEquals(P2pGroupId.AppLink, done.groupId)
        }
    }
    println("p2p-e2e: ${joiner.name} linked to ${member.name} in ${started.elapsedNow()}")
}

/** Waits for a step that matches [predicate]; a failed enrollment fails at once. */
private suspend fun KeyguardEnrollment.await(
    what: String,
    predicate: (EnrollmentStep) -> Boolean,
): EnrollmentStep {
    val step = withTimeoutOrNull(ENROLLMENT_STEP_TIMEOUT) {
        step.first { predicate(it) || it is EnrollmentStep.Failed }
    } ?: fail("The enrollment did not $what in $ENROLLMENT_STEP_TIMEOUT; it is at ${step.value}")
    if (step is EnrollmentStep.Failed && !predicate(step)) fail("The enrollment failed to $what: ${step.reason}")
    return step
}

/** Long enough for any transport to connect and the handshake to finish. */
private val ENROLLMENT_STEP_TIMEOUT = 90.seconds

private val SCENARIO_TIMEOUT = 5.minutes

private val DIAGNOSTICS_TIMEOUT = 10.seconds
