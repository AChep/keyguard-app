package com.artemchep.keyguard.feature.applink.connection

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DAppLinkThisDevice
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.GetAppLinkThisDevice
import com.artemchep.keyguard.common.usecase.PutAppLinkNetwork
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.auth.keyguard.KeyguardProducerTestScope
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AppLinkConnectionStateProducerTest {
    @Test
    fun `saves changed settings, then closes`() = runTest {
        val harness = ConnectionHarness(this)
        harness.network.value = P2pNetwork(relayUrl = "wss://old.example.com")
        val states = harness.states()
        runCurrent()

        val state = states.ok()
        assertEquals("wss://old.example.com", state.network.relayUrl.text)
        assertNull(state.onSave, "Nothing changed")

        state.network.relayUrl.onChange!!("http://insecure.example.com")
        runCurrent()
        assertNull(states.ok().onSave, "The URL is invalid")

        states.ok().network.relayUrl.onChange!!("wss://relay.example.com")
        states.ok().network.accessToken.onChange!!("token")
        states.ok().network.allowLan.onChange!!(false)
        runCurrent()
        assertNotNull(states.ok().onSave).invoke()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(
            listOf(
                P2pNetwork(
                    relayUrl = "wss://relay.example.com",
                    accessToken = "token",
                    allowLan = false,
                ),
            ),
            harness.saved,
        )
        assertIs<NavigationIntent.PopById>(harness.screen.navigations.last())
    }

    @Test
    fun `saving keeps relay storage, and its switch saves too`() = runTest {
        val harness = ConnectionHarness(this)
        harness.network.value = P2pNetwork(relayStorage = true)
        val states = harness.states()
        runCurrent()

        assertTrue(states.ok().network.relayStorage.checked)
        states.ok().network.allowLan.onChange!!(false)
        runCurrent()
        assertNotNull(states.ok().onSave).invoke()
        advanceTimeBy(1.seconds)
        runCurrent()

        states.ok().network.relayStorage.onChange!!(false)
        runCurrent()
        assertNotNull(states.ok().onSave).invoke()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(
            listOf(
                P2pNetwork(allowLan = false, relayStorage = true),
                P2pNetwork(allowLan = false, relayStorage = false),
            ),
            harness.saved,
        )
    }

    @Test
    fun `blank fields are the defaults, so they are no change`() = runTest {
        val harness = ConnectionHarness(this)
        val states = harness.states()
        runCurrent()

        states.ok().network.iceUrl.onChange!!("   ")
        runCurrent()

        assertNull(states.ok().onSave)
    }

    private fun StateFlow<Loadable<AppLinkConnectionState>?>.ok() =
        assertNotNull(value?.getOrNull())
}

private class ConnectionHarness(
    testScope: TestScope,
) {
    val screen = KeyguardProducerTestScope(testScope)

    val network = MutableStateFlow(P2pNetwork())

    val saved = mutableListOf<P2pNetwork>()

    private val getAppLinkThisDevice = object : GetAppLinkThisDevice {
        override fun invoke() = network
            .map { DAppLinkThisDevice(label = "Laptop", network = it) }
    }

    private val putAppLinkNetwork = object : PutAppLinkNetwork {
        override fun invoke(network: P2pNetwork): IO<Unit> = {
            saved += network
            this@ConnectionHarness.network.value = network
        }
    }

    suspend fun states(): StateFlow<Loadable<AppLinkConnectionState>?> = screen.create()
        .appLinkConnectionStateProducer(
            getAppLinkThisDevice = getAppLinkThisDevice,
            putAppLinkNetwork = putAppLinkNetwork,
        )
        .stateIn(screen.scope, SharingStarted.Eagerly, null)
}
