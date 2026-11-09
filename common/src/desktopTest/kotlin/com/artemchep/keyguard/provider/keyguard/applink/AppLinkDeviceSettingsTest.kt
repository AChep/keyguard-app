package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.usecase.GetDefaultDeviceLabel
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.provider.bitwarden.usecase.PutKeyguardDeviceLabelImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutKeyguardNetworkSettingsImpl
import com.artemchep.keyguard.provider.keyguard.P2pMembershipStore
import com.artemchep.keyguard.provider.keyguard.QueueP2pSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLinkDeviceSettingsTest {
    @Test
    fun `this device is set up before the first link`() = runTest {
        ThisDeviceHarness(this).use { harness ->
            assertEquals(DEFAULT_LABEL, harness.thisDevice().label)
            assertEquals(P2pNetwork(), harness.thisDevice().network)

            harness.putLabel("  Work laptop  ").bind()
            harness.putNetwork(P2pNetwork(relayUrl = " wss://relay.example.com ", allowLan = false)).bind()

            val thisDevice = harness.thisDevice()
            assertEquals("Work laptop", thisDevice.label)
            assertEquals(P2pNetwork(relayUrl = "wss://relay.example.com", allowLan = false), thisDevice.network)
            assertNull(harness.device.service.membership(), "Setting up links nothing")
        }
    }

    @Test
    fun `the link wins while there is one, takes the changes, and unlinking keeps them`() = runTest {
        ThisDeviceHarness(this).use { harness ->
            harness.putLabel("Old name").bind()
            // Pairing founds the link with the settings of this device.
            harness.device.service.prepareLink("Laptop", P2pNetwork(allowLan = false), TEST_MASTER_PASSWORD)
            assertEquals("Laptop", harness.thisDevice().label)
            assertEquals(P2pNetwork(allowLan = false), harness.thisDevice().network)

            harness.putLabel("Work laptop").bind()
            harness.putNetwork(P2pNetwork(relayUrl = "wss://relay.example.com")).bind()
            advanceUntilIdle()
            val membership = harness.device.service.membership()!!
            assertEquals("Work laptop", membership.device.label)
            assertEquals("wss://relay.example.com", membership.network.relayUrl)

            harness.device.service.dissolve()
            assertNull(harness.device.service.membership())
            val thisDevice = harness.thisDevice()
            assertEquals("Work laptop", thisDevice.label)
            assertEquals(P2pNetwork(relayUrl = "wss://relay.example.com"), thisDevice.network)
        }
    }

    @Test
    fun `unlinking keeps what the link adopted`() = runTest {
        ThisDeviceHarness(this).use { harness ->
            // Founded before this device had its settings saved, say by an older version.
            harness.device.service.prepareLink(
                deviceLabel = "Laptop",
                network = P2pNetwork(iceUrl = "https://ice.example.com"),
                password = TEST_MASTER_PASSWORD,
            )

            harness.device.service.dissolve()

            val thisDevice = harness.thisDevice()
            assertEquals("Laptop", thisDevice.label)
            assertEquals("https://ice.example.com", thisDevice.network.iceUrl)
        }
    }

    @Test
    fun `a new system name never replaces a saved or linked device name`() = runTest {
        ThisDeviceHarness(this).use { harness ->
            harness.defaultLabel = "First system name"
            assertEquals("First system name", harness.thisDevice().label)

            // Even an older generic name may have been chosen by the user.
            harness.putLabel("Computer").bind()
            harness.defaultLabel = "Second system name"
            assertEquals("Computer", harness.thisDevice().label)

            harness.device.service.prepareLink("Linked name", P2pNetwork(), TEST_MASTER_PASSWORD)
            harness.defaultLabel = "Third system name"
            assertEquals("Linked name", harness.thisDevice().label)

            harness.device.service.dissolve()
            assertEquals("Linked name", harness.thisDevice().label)
        }
    }

    @Test
    fun `relay storage is chosen before the first link and changed in it`() = runTest {
        ThisDeviceHarness(this).use { harness ->
            harness.putNetwork(P2pNetwork(relayStorage = true)).bind()
            assertTrue(harness.thisDevice().network.relayStorage)

            harness.device.service.prepareLink("Laptop", harness.thisDevice().network, TEST_MASTER_PASSWORD)
            assertTrue(harness.device.service.membership()!!.network.relayStorage)

            harness.putNetwork(P2pNetwork(relayStorage = false)).bind()
            advanceUntilIdle()
            assertFalse(harness.device.service.membership()!!.network.relayStorage)
            assertFalse(harness.thisDevice().network.relayStorage)
        }
    }

    private companion object {
        const val DEFAULT_LABEL = "Computer"
    }
}

private class ThisDeviceHarness(
    testScope: TestScope,
) : AutoCloseable {
    var defaultLabel = "Computer"

    val device = AppLinkTestDevice("Laptop")

    private val membershipStore = P2pMembershipStore(device.dbManager)

    private val settings = device.components.deviceSettings

    private val windowCoroutineScope = object : WindowCoroutineScope, CoroutineScope by testScope {}

    private val getThisDevice = GetAppLinkThisDeviceImpl(
        membershipStore = membershipStore,
        settings = settings,
        getDefaultDeviceLabel = object : GetDefaultDeviceLabel {
            override fun invoke(): IO<String> = ioEffect { defaultLabel }
        },
    )

    val putLabel = PutAppLinkDeviceLabelImpl(
        membershipStore = membershipStore,
        settings = settings,
        putKeyguardDeviceLabel = PutKeyguardDeviceLabelImpl(
            db = device.dbManager,
            membershipStore = membershipStore,
            crypto = device.crypto,
            queueSync = QueueP2pSync { _: P2pGroupId -> ioEffect { } },
            windowCoroutineScope = windowCoroutineScope,
        ),
    )

    val putNetwork = PutAppLinkNetworkImpl(
        membershipStore = membershipStore,
        settings = settings,
        putKeyguardNetworkSettings = PutKeyguardNetworkSettingsImpl(
            membershipStore = membershipStore,
        ),
    )

    suspend fun thisDevice() = getThisDevice().first()

    override fun close() = device.close()
}
