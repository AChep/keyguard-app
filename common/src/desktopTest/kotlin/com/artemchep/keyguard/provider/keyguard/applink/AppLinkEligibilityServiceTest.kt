package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardPairingException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppLinkEligibilityServiceTest {
    private val customNetwork = P2pNetwork(relayUrl = "wss://relay.example.com")

    @Test
    fun `unpaid devices can link and sync through a self-hosted relay`() = runTest {
        AppLinkTestDevice("A", premium = Premium.NONE).use { a ->
            AppLinkTestDevice("B", premium = Premium.INCLUDED).use { b ->
                a.components.deviceSettings.setNetwork(customNetwork).bind()
                b.components.deviceSettings.setNetwork(customNetwork).bind()
                a.service.prepareLink(a.name, customNetwork, TEST_MASTER_PASSWORD)?.close()
                a.addForwarder("Work")
                b.service.checkJoinable()
                val device = b.newDevice()
                // The bundle carries the relay to adopt when no override is supplied.
                b.service.import(a.admit(device), device, P2pNetwork())
                a.exchangeWith(b)

                assertEquals(customNetwork, b.membership().network)
                assertEquals(listOf("Work"), b.forwarderList().map { it.name })
                assertEquals(DAppLinkState.Linked(peers = 1), a.service.observeState().first())
                // A linked device uses its membership's relay, even if the caller passes defaults.
                assertNull(a.service.prepareLink(a.name, P2pNetwork(), TEST_MASTER_PASSWORD))
            }
        }
    }

    @Test
    fun `unpaid devices cannot import a link that adopts the hosted relay`() = runTest {
        AppLinkTestDevice("Member").use { member ->
            member.link()
            listOf(Premium.NONE, Premium.INCLUDED).forEach { premium ->
                AppLinkTestDevice("Joiner", premium = premium).use { joiner ->
                    joiner.components.deviceSettings.setNetwork(customNetwork).bind()
                    joiner.service.checkJoinable()
                    val device = joiner.newDevice()
                    val bundle = member.admit(device, save = false)

                    assertFailsWith<KeyguardPairingException> {
                        joiner.service.import(bundle, device, P2pNetwork())
                    }
                    assertNull(joiner.service.membership())
                }
            }
        }
    }

    @Test
    fun `a custom relay argument cannot override the hosted relay of an existing link`() = runTest {
        AppLinkTestDevice("A").use { a ->
            AppLinkTestDevice("B").use { b ->
                a.link()
                b.join(a)
                a.premium.value = Premium.INCLUDED

                assertFailsWith<KeyguardPairingException> {
                    a.service.prepareLink(a.name, customNetwork, TEST_MASTER_PASSWORD)
                }
                assertEquals(AppLinkTestDevice.NETWORK, a.membership().network)
            }
        }
    }
}
