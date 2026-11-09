package com.artemchep.keyguard.feature.applink

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.model.DAppLinkThisDevice
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.GetAppLinkState
import com.artemchep.keyguard.common.usecase.GetAppLinkSyncCategories
import com.artemchep.keyguard.common.usecase.GetAppLinkThisDevice
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.PutAppLinkDeviceLabel
import com.artemchep.keyguard.common.usecase.PutAppLinkSyncCategory
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.auth.keyguard.KeyguardProducerTestScope
import com.artemchep.keyguard.feature.auth.keyguard.TEST_TRANSLATED
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactoryDefault
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultReceiver
import com.artemchep.keyguard.feature.p2psync.model.DeviceRecordRules
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_connection_default_relay
import com.artemchep.keyguard.res.app_link_device_name_error_invalid
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AppLinkStateProducerTest {
    @Test
    fun `before the first link, this device and what it syncs are set up`() = runTest {
        val harness = AppLinkHarness(this)
        val states = harness.states()
        runCurrent()

        val state = states.ok()
        assertIs<AppLinkState.Content.Unlinked>(state.content)
        assertEquals("Laptop", state.thisDevice.label)
        assertEquals(TEST_TRANSLATED, state.thisDevice.relayText)
        assertTrue(Res.string.app_link_connection_default_relay in harness.screen.translated)
        assertNull(state.thisDevice.lanText, "The local network is allowed")
        assertEquals(DAppLinkSyncCategory.entries, state.categories.map { it.category })

        val wordlists = state.categories.single { it.category == DAppLinkSyncCategory.WORDLISTS }
        assertNotNull(wordlists.onCheckedChange).invoke(false)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(DAppLinkSyncCategory.WORDLISTS to false), harness.switched)
    }

    @Test
    fun `renames this device for every link`() = runTest {
        val harness = AppLinkHarness(this)
        val states = harness.states()
        runCurrent()

        states.ok().thisDevice.onRename()
        runCurrent()
        harness.confirm(ConfirmationResult.Confirm(mapOf("label" to "Work laptop")))
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(listOf("Work laptop"), harness.renamed)
        assertEquals("Work laptop", states.ok().thisDevice.label)
    }

    @Test
    fun `the rename dialog takes only a name every device takes`() = runTest {
        val harness = AppLinkHarness(this)
        val states = harness.states()
        runCurrent()

        states.ok().thisDevice.onRename()
        runCurrent()
        val validate = assertNotNull(harness.lastConfirmation().args.validate)
        assertEquals(emptyMap(), validate(mapOf("label" to "  Work laptop  ")))
        assertEquals(emptyMap(), validate(mapOf("label" to "😀".repeat(DeviceRecordRules.MAX_LABEL_LENGTH))))
        val invalid = listOf("line\nbreak", "rtl\u202Eoverride", "x".repeat(DeviceRecordRules.MAX_LABEL_LENGTH + 1))
        invalid.forEach { label ->
            val errors = validate(mapOf("label" to label))
            assertEquals(mapOf("label" to TextHolder.Value(TEST_TRANSLATED)), errors, label)
        }
        // The error names the longest name the devices take.
        val error = harness.screen.translated.indexOf(Res.string.app_link_device_name_error_invalid)
        assertEquals(listOf<Any?>(DeviceRecordRules.MAX_LABEL_LENGTH), harness.screen.translatedArgs[error])
    }

    @Test
    fun `a custom relay shows its host, and the local network switch`() = runTest {
        val harness = AppLinkHarness(this)
        harness.network.value = P2pNetwork(relayUrl = "wss://relay.example.com/path", allowLan = false)
        val states = harness.states()
        runCurrent()

        val thisDevice = states.ok().thisDevice
        assertEquals("relay.example.com", thisDevice.relayText)
        assertEquals(TEST_TRANSLATED, thisDevice.lanText)
    }

    @Test
    fun `opens the connection`() = runTest {
        val harness = AppLinkHarness(this)
        val states = harness.states()
        runCurrent()

        states.ok().thisDevice.onConnection()
        val connection = assertIs<NavigationIntent.NavigateToRoute>(harness.screen.navigations.last())
        assertEquals(AppLinkConnectionRoute, connection.route)
    }

    @Test
    fun `a popup picks which device shows the code, then the pairing opens`() = runTest {
        val harness = AppLinkHarness(this)
        val states = harness.states()
        runCurrent()

        val content = assertIs<AppLinkState.Content.Unlinked>(states.ok().content)
        val link = content.linkActions.filterIsInstance<FlatItemAction>()
        assertEquals(listOf("scan", "show"), link.map { it.id })
        assertNotNull(link.first().onClick).invoke()
        val scan = assertIs<NavigationIntent.NavigateToRoute>(harness.screen.navigations.last())
        assertEquals(AppLinkPairRoute(scan = true), scan.route)

        assertNotNull(link.last().onClick).invoke()
        val show = assertIs<NavigationIntent.NavigateToRoute>(harness.screen.navigations.last())
        assertEquals(AppLinkPairRoute(scan = false), show.route)
    }

    @Test
    fun `pairing through the hosted relay needs a purchase`() = runTest {
        listOf(Premium.NONE, Premium.INCLUDED).forEach { premium ->
            val harness = AppLinkHarness(this, premium = premium)
            val states = harness.states()
            runCurrent()

            val state = states.ok()
            assertTrue(state.premiumRequired)
            val content = assertIs<AppLinkState.Content.Unlinked>(state.content)
            assertTrue(content.linkActions.isEmpty())
        }
    }

    @Test
    fun `changing the relay or purchase status updates pairing availability`() = runTest {
        val harness = AppLinkHarness(this, premium = Premium.INCLUDED)
        val states = harness.states()
        runCurrent()
        assertTrue(states.ok().premiumRequired)

        harness.network.value = P2pNetwork(relayUrl = "wss://relay.example.com")
        runCurrent()
        assertFalse(states.ok().premiumRequired)
        assertEquals(2, assertIs<AppLinkState.Content.Unlinked>(states.ok().content).linkActions.size)

        harness.premium.value = Premium.NONE
        runCurrent()
        assertFalse(states.ok().premiumRequired, "Self-hosting needs no Premium status")

        harness.network.value = P2pNetwork(relayUrl = "https://RELAY.KEYGUARD.DEV.:8443/custom/v1/ws")
        runCurrent()
        assertTrue(states.ok().premiumRequired)

        harness.premium.value = Premium.PURCHASED
        runCurrent()
        assertFalse(states.ok().premiumRequired)

        harness.premium.value = Premium.INCLUDED
        runCurrent()
        assertTrue(states.ok().premiumRequired)
    }

    @Test
    fun `a linked app shows the same device and switches`() = runTest {
        val harness = AppLinkHarness(this)
        harness.linkState.value = DAppLinkState.Linked(peers = 1)
        val states = harness.states()
        runCurrent()

        val state = states.ok()
        assertEquals(AppLinkState.Content.Linked, state.content)
        assertEquals("Laptop", state.thisDevice.label)
        assertEquals(DAppLinkSyncCategory.entries.size, state.categories.size)
    }

    private fun StateFlow<Loadable<AppLinkState>?>.ok() =
        assertNotNull(value?.getOrNull())
}

private class AppLinkHarness(
    testScope: TestScope,
    premium: Premium = Premium.PURCHASED,
) {
    val screen = KeyguardProducerTestScope(testScope)

    val premium = MutableStateFlow(premium)

    val linkState = MutableStateFlow<DAppLinkState>(DAppLinkState.NotLinked)

    val label = MutableStateFlow("Laptop")

    val network = MutableStateFlow(P2pNetwork())

    val switches = MutableStateFlow(DAppLinkSyncCategory.entries.associateWith { true })

    val switched = mutableListOf<Pair<DAppLinkSyncCategory, Boolean>>()

    val renamed = mutableListOf<String>()

    private val useCases = AppLinkUseCases(
        getAppLinkState = object : GetAppLinkState {
            override fun invoke() = linkState
        },
        getAppLinkSyncCategories = object : GetAppLinkSyncCategories {
            override fun invoke() = switches
        },
        putAppLinkSyncCategory = object : PutAppLinkSyncCategory {
            override fun invoke(category: DAppLinkSyncCategory, enabled: Boolean): IO<Unit> = {
                switched += category to enabled
                switches.value = switches.value + (category to enabled)
            }
        },
        getAppLinkThisDevice = object : GetAppLinkThisDevice {
            override fun invoke() = combine(label, network) { l, n ->
                DAppLinkThisDevice(label = l, network = n)
            }
        },
        putAppLinkDeviceLabel = object : PutAppLinkDeviceLabel {
            override fun invoke(label: String): IO<Unit> = {
                renamed += label
                this@AppLinkHarness.label.value = label
            }
        },
        getPurchased = object : GetPurchased {
            override fun invoke() = this@AppLinkHarness.premium
        },
    )

    fun states(): StateFlow<Loadable<AppLinkState>?> = screen.create()
        .appLinkStateProducer(
            useCases = useCases,
            confirmationRouteFactory = ConfirmationRouteFactoryDefault,
        )
        .stateIn(screen.scope, SharingStarted.Eagerly, null)

    /** The last confirmation dialog. */
    @Suppress("UNCHECKED_CAST")
    fun lastConfirmation(): ConfirmationRoute {
        val intent = assertIs<NavigationIntent.NavigateToRoute>(screen.navigations.last())
        val receiver = intent.route as RouteResultReceiver<ConfirmationResult>
        return assertIs<ConfirmationRoute>(receiver.innerRoute)
    }

    /** Answers the last confirmation dialog with [result]. */
    @Suppress("UNCHECKED_CAST")
    fun confirm(result: ConfirmationResult) {
        val intent = assertIs<NavigationIntent.NavigateToRoute>(screen.navigations.last())
        val receiver = intent.route as RouteResultReceiver<ConfirmationResult>
        receiver.resultTransmitter(result)
    }
}
