package com.artemchep.keyguard.provider.keyguard.enroll

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.p2psync.enroll.DisplayMode
import com.artemchep.keyguard.feature.p2psync.model.PairingCode
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkTestDevice
import com.artemchep.keyguard.provider.keyguard.applink.TEST_MASTER_PASSWORD
import com.artemchep.keyguard.provider.keyguard.session.realTime
import com.artemchep.keyguard.provider.keyguard.session.testRoomConnector
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayConnector
import com.artemchep.keyguard.provider.keyguard.transport.TestRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class EnrollmentEligibilityTest {
    private val relay = TestRelay()

    private val network = P2pNetwork(relayUrl = "wss://relay.example.com")

    @Test
    fun `unpaid apps can pair through a self-hosted relay`() = realTime {
        device("Laptop", Premium.NONE).use { laptop ->
            device("Phone", Premium.INCLUDED).use { phone ->
                laptop.components.deviceSettings.setNetwork(network).bind()
                phone.components.deviceSettings.setNetwork(network).bind()
                coroutineScope {
                    val showing = laptop.pairApp(this)
                    showing.showCode()
                    val code = assertIs<EnrollmentStep.ShowCode>(showing.await { it is EnrollmentStep.ShowCode }).code
                    val scanning = phone.pairApp(this)
                    scanning.scanCode()
                    scanning.await { it == EnrollmentStep.Scanning }
                    scanning.submitCode(code)
                    listOf(scanning, showing).forEach { enrollment ->
                        val verify = assertIs<EnrollmentStep.Verify>(enrollment.await { it is EnrollmentStep.Verify })
                        when (verify.display) {
                            DisplayMode.DISPLAYER -> enrollment.enterDigits(verify.sas)
                            DisplayMode.SCANNER -> enrollment.approve()
                        }
                    }
                    listOf(scanning, showing).forEach { enrollment ->
                        assertEquals(
                            EnrollmentStep.Done(P2pGroupId.AppLink),
                            enrollment.await { it is EnrollmentStep.Done || it is EnrollmentStep.Failed },
                        )
                    }
                }
                assertEquals(network, laptop.membership().network)
                assertEquals(network, phone.membership().network)
            }
        }
    }

    @Test
    fun `a custom relay setting does not let an unpaid scanner connect to the hosted relay`() = realTime {
        val opened = AtomicInteger()
        val recording = KeyguardRelayConnector { endpoint, room ->
            opened.incrementAndGet()
            relay.connector.open(endpoint, room)
        }
        device("Phone", Premium.INCLUDED, recording).use { joiner ->
            joiner.components.deviceSettings.setNetwork(network).bind()
            coroutineScope {
                val joining = joiner.pairApp(this)
                joining.scanCode()
                joining.await { it == EnrollmentStep.Scanning }
                val hostedCode = PairingCode(
                    psk = Random.nextBytes(PairingCode.KEY_SIZE),
                    pub = Random.nextBytes(PairingCode.KEY_SIZE),
                    member = true,
                    expiresAt = Clock.System.now().epochSeconds + 60L,
                    scope = SyncScope.APP,
                )
                joining.submitCode(hostedCode.encode())
                assertIs<EnrollmentStep.Failed>(joining.await { it is EnrollmentStep.Failed })
            }
            assertEquals(0, opened.get())
            assertNull(joiner.service.membership())
        }
    }

    private fun device(
        name: String,
        premium: Premium,
        connector: KeyguardRelayConnector = relay.connector,
    ) = AppLinkTestDevice(
        name = name,
        premium = premium,
        realTime = true,
        roomConnector = testRoomConnector(connector),
    )

    private fun AppLinkTestDevice.pairApp(scope: CoroutineScope) = components.enrollment
        .pairApp(name, network, password = { TEST_MASTER_PASSWORD }, scope)

    private suspend fun KeyguardEnrollment.await(predicate: (EnrollmentStep) -> Boolean) =
        withTimeout(40.seconds) { step.first(predicate) }
}
