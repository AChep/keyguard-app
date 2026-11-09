package com.artemchep.keyguard.feature.keyguardsync.devices

import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DKeyguardDevice
import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStorageStatus
import com.artemchep.keyguard.common.model.DKeyguardSyncStatus
import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetKeyguardDevices
import com.artemchep.keyguard.common.usecase.GetKeyguardSyncStatus
import com.artemchep.keyguard.common.usecase.LeaveKeyguardGroup
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.RevokeKeyguardDevice
import com.artemchep.keyguard.feature.applink.AppLinkPairRoute
import com.artemchep.keyguard.feature.applink.AppLinkDevicesTarget
import com.artemchep.keyguard.feature.auth.keyguard.KeyguardProducerTestScope
import com.artemchep.keyguard.feature.auth.keyguard.TEST_TRANSLATED
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactoryDefault
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultReceiver
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_device_connected_via
import com.artemchep.keyguard.res.app_link_device_never_seen
import com.artemchep.keyguard.res.app_link_status_relay_storage_paused
import com.artemchep.keyguard.res.app_link_status_relay_storage_starting
import com.artemchep.keyguard.res.app_link_status_relay_storage_waiting
import com.artemchep.keyguard.res.app_link_status_relay_storage_waiting_plural
import com.artemchep.keyguard.res.app_link_transport_relay
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val GROUP_ID = P2pGroupId.AppLink

private val ADDED_AT = Instant.fromEpochMilliseconds(1_700_000_000_000L)

private const val PASSWORD = "master password"

// One test per behavior of the screen.
@Suppress("TooManyFunctions")
class KeyguardDevicesStateProducerTest {
    @Test
    fun `summarizes the devices of the group`() {
        val summary = keyguardDevicesSummary(
            devices = listOf(SELF, PHONE, DESK, REMOVED),
            peers = mapOf(
                PHONE.id to peer(PHONE.id, connected(DKeyguardTransport.LAN, upToDate = true)),
                DESK.id to peer(DESK.id, connected(DKeyguardTransport.TURN, upToDate = false)),
                REMOVED.id to peer(REMOVED.id, connected(DKeyguardTransport.RELAY, upToDate = true)),
            ),
            relay = DKeyguardRelayStatus(DKeyguardRelayStatus.State.CONNECTED),
        )

        assertEquals(
            KeyguardDevicesSummary(total = 2, upToDate = 1, removed = false),
            summary,
        )
        val removed = keyguardDevicesSummary(
            devices = listOf(SELF.copy(revoked = true), PHONE),
            peers = emptyMap(),
            relay = DKeyguardRelayStatus(DKeyguardRelayStatus.State.CONNECTED),
        )
        assertTrue(removed.removed)
        assertFalse(removed.removedByRemoved)
        // A device that was removed already removed this one.
        val byRemoved = keyguardDevicesSummary(
            devices = listOf(SELF.copy(revoked = true, removedByRemoved = true), PHONE),
            peers = emptyMap(),
            relay = DKeyguardRelayStatus(DKeyguardRelayStatus.State.CONNECTED),
        )
        assertTrue(byRemoved.removed && byRemoved.removedByRemoved)
    }

    @Test
    fun `the health follows the relay`() {
        fun health(relay: DKeyguardRelayStatus.State, upToDate: Int) = keyguardDevicesHealth(
            relay = relay,
            summary = KeyguardDevicesSummary(
                total = 3,
                upToDate = upToDate,
                removed = false,
            ),
        )

        val connected = DKeyguardRelayStatus.State.CONNECTED
        assertEquals(KeyguardDevicesState.Health.OK, health(connected, upToDate = 3))
        assertEquals(KeyguardDevicesState.Health.WARNING, health(connected, upToDate = 1))
        assertEquals(KeyguardDevicesState.Health.ERROR, health(DKeyguardRelayStatus.State.FAILED, 0))
        assertEquals(KeyguardDevicesState.Health.ERROR, health(DKeyguardRelayStatus.State.INCOMPATIBLE, 0))
        assertEquals(KeyguardDevicesState.Health.WARNING, health(DKeyguardRelayStatus.State.CONNECTING, 0))
        assertEquals(KeyguardDevicesState.Health.NEUTRAL, health(DKeyguardRelayStatus.State.OFFLINE, 0))
    }

    @Test
    fun `lists the active devices with this one first`() = runTest {
        val harness = DevicesHarness(this)
        val states = harness.states()
        runCurrent()

        val state = states.ok()
        assertEquals(listOf(SELF.id, DESK.id, PHONE.id), state.devices.map { it.id })
        val self = state.devices[0]
        assertTrue(self.isThisDevice)
        assertNull(self.health)
        assertNull(self.statusText)
        assertNull(self.lastSeen)
        assertNull(self.onRemove)
        assertTrue(self.dropdown.isEmpty(), "The linked devices screen renames this device")

        val phone = state.devices.single { it.id == PHONE.id }
        assertEquals(KeyguardDevicesState.Health.OK, phone.health)
        assertEquals(TEST_TRANSLATED, phone.statusText)
        assertNull(phone.lastSeen, "Connected now")
        assertNotNull(phone.onRemove)
        assertEquals(PHONE.fingerprint, phone.fingerprint)

        val desk = state.devices.single { it.id == DESK.id }
        assertEquals(KeyguardDevicesState.Health.NEUTRAL, desk.health)
        assertEquals(TEST_TRANSLATED, desk.statusText)
        assertNull(desk.lastSeen)
        assertTrue(Res.string.app_link_device_connected_via in harness.screen.translated)
        assertTrue(Res.string.app_link_device_never_seen in harness.screen.translated)

        assertEquals(KeyguardDevicesState.Health.WARNING, state.header.health)
        assertEquals(1, state.header.upToDate)
        assertEquals(2, state.header.total)
        assertNotNull(state.header.upToDateText)
        assertTrue(state.addDeviceActions.isNotEmpty())
        assertTrue(!state.removed)
        assertEquals(
            KeyguardDevicesState.Texts(
                addDeviceTitle = TEST_TRANSLATED,
                leaveTitle = TEST_TRANSLATED,
                leaveText = TEST_TRANSLATED,
            ),
            state.texts,
        )
    }

    @Test
    fun `a device seen before says when and over what`() = runTest {
        val harness = DevicesHarness(this)
        harness.peers.value = PEERS + mapOf(
            PHONE.id to peer(PHONE.id, connected(DKeyguardTransport.TURN, upToDate = false)),
            DESK.id to peer(
                DESK.id,
                DKeyguardPeerStatus.Connection.Connecting,
                lastTransport = DKeyguardTransport.RELAY,
            ),
        )
        val states = harness.states()
        runCurrent()

        val phone = states.ok().devices.single { it.id == PHONE.id }
        assertEquals(KeyguardDevicesState.Health.WARNING, phone.health)
        assertEquals(TEST_TRANSLATED, phone.statusText)

        val desk = states.ok().devices.single { it.id == DESK.id }
        assertEquals(KeyguardDevicesState.Health.WARNING, desk.health)
        assertNull(desk.statusText)
        assertEquals(
            KeyguardDevicesState.LastSeen(at = LAST_SEEN_AT, transportText = TEST_TRANSLATED),
            desk.lastSeen,
        )
        assertTrue(Res.string.app_link_transport_relay in harness.screen.translated)
    }


    @Test
    fun `removing a device asks first`() = runTest {
        val harness = DevicesHarness(this)
        val states = harness.states()
        runCurrent()

        states.ok().devices.single { it.id == PHONE.id }.onRemove!!()
        runCurrent()
        assertTrue(harness.revoked.isEmpty())

        harness.confirm(ConfirmationResult.Confirm(emptyMap()))
        runCurrent()
        // Then for the master password; a wrong one is asked again.
        assertTrue(harness.revoked.isEmpty())
        harness.confirm(ConfirmationResult.Confirm(mapOf("keyguard_authority_password:0" to "wrong")))
        runCurrent()
        assertTrue(harness.revoked.isEmpty())
        harness.confirm(ConfirmationResult.Confirm(mapOf("keyguard_authority_password:1" to PASSWORD)))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(Triple(GROUP_ID, setOf(PHONE.id), PASSWORD)), harness.revoked)
    }

    @Test
    fun `removing a device stops if the master password is not given`() = runTest {
        val harness = DevicesHarness(this)
        val states = harness.states()
        runCurrent()

        states.ok().devices.single { it.id == PHONE.id }.onRemove!!()
        runCurrent()
        harness.confirm(ConfirmationResult.Confirm(emptyMap()))
        runCurrent()
        harness.confirm(ConfirmationResult.Deny)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(harness.revoked.isEmpty())
    }

    @Test
    fun `removing a device offers to remove the devices it added`() = runTest {
        val harness = DevicesHarness(this)
        val tablet = device(id = "tablet", label = "Tablet", addedBy = PHONE.id)
        val watch = device(id = "watch", label = "Watch", addedBy = PHONE.id)
        harness.devices.value += listOf(tablet, watch)
        val states = harness.states()
        runCurrent()

        states.ok().devices.single { it.id == PHONE.id }.onRemove!!()
        runCurrent()
        harness.confirm(ConfirmationResult.Confirm(mapOf("remove_added:${watch.id}" to true)))
        runCurrent()
        harness.confirm(ConfirmationResult.Confirm(mapOf("keyguard_authority_password:0" to PASSWORD)))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(Triple(GROUP_ID, setOf(PHONE.id, watch.id), PASSWORD)), harness.revoked)
    }

    @Test
    fun `leaving the group asks first, then for the master password`() = runTest {
        val harness = DevicesHarness(this)
        val states = harness.states()
        runCurrent()

        states.ok().onLeave!!()
        runCurrent()
        harness.confirm(ConfirmationResult.Deny)
        runCurrent()
        assertTrue(harness.left.isEmpty())

        states.ok().onLeave!!()
        runCurrent()
        harness.confirm(ConfirmationResult.Confirm(emptyMap()))
        runCurrent()
        harness.confirm(ConfirmationResult.Confirm(mapOf("keyguard_authority_password:0" to PASSWORD)))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf<Pair<P2pGroupId, String?>>(GROUP_ID to PASSWORD), harness.left)
    }

    @Test
    fun `adding a device may need premium, and shows or scans a code`() = runTest {
        val harness = DevicesHarness(this)
        harness.canAddDevice.value = false
        val states = harness.states()
        runCurrent()
        assertTrue(states.ok().addDeviceActions.isEmpty())

        harness.canAddDevice.value = true
        runCurrent()
        val actions = states.ok().addDeviceActions.filterIsInstance<FlatItemAction>()
        assertEquals(listOf("scan", "show"), actions.map { it.id })
        assertNotNull(actions.first().onClick).invoke()
        assertNotNull(actions.last().onClick).invoke()

        val routes = harness.screen.navigations.map { assertIs<NavigationIntent.NavigateToRoute>(it).route }
        assertEquals(listOf(AppLinkPairRoute(scan = true), AppLinkPairRoute(scan = false)), routes)
    }

    @Test
    fun `the header says how relay storage is doing, unless it is off`() = runTest {
        val harness = DevicesHarness(this)
        val states = harness.states()
        runCurrent()
        assertNull(states.ok().header.storageText, "No session runs")

        harness.storage.value = DKeyguardRelayStorageStatus.Off
        runCurrent()
        assertNull(states.ok().header.storageText)

        harness.storage.value = DKeyguardRelayStorageStatus.Waiting(emptyList())
        runCurrent()
        assertEquals(TEST_TRANSLATED, states.ok().header.storageText)
        assertTrue(Res.string.app_link_status_relay_storage_starting in harness.screen.translated)

        harness.storage.value = DKeyguardRelayStorageStatus.Waiting(listOf(PHONE.label))
        runCurrent()
        assertTrue(Res.string.app_link_status_relay_storage_waiting in harness.screen.translated)

        harness.storage.value = DKeyguardRelayStorageStatus.Waiting(listOf(PHONE.label, DESK.label))
        runCurrent()
        assertTrue(Res.plurals.app_link_status_relay_storage_waiting_plural in harness.screen.translated)

        harness.storage.value = DKeyguardRelayStorageStatus.Paused(until = LAST_SEEN_AT)
        runCurrent()
        assertEquals(TEST_TRANSLATED, states.ok().header.storageText)
        assertTrue(Res.string.app_link_status_relay_storage_paused in harness.screen.translated)
    }

    @Test
    fun `a removed device can only leave`() = runTest {
        val harness = DevicesHarness(this)
        harness.relay.value = DKeyguardRelayStatus(DKeyguardRelayStatus.State.CONNECTED, revoked = true)
        val states = harness.states()
        runCurrent()

        val state = states.ok()
        assertTrue(state.removed)
        assertTrue(state.addDeviceActions.isEmpty())
        assertTrue(state.devices.all { it.onRemove == null })
        assertNotNull(state.onLeave)
    }

    private fun StateFlow<Loadable<KeyguardDevicesState>?>.ok() =
        assertNotNull(value?.getOrNull())

    private companion object {
        val LAST_SEEN_AT: Instant = Instant.fromEpochMilliseconds(1_700_000_100_000L)

        val SELF = device(id = "self", label = "Laptop", isThisDevice = true)
        val PHONE = device(id = "phone", label = "Phone")
        val DESK = device(id = "desk", label = "Desk PC", platform = "desktop")
        val REMOVED = device(id = "removed", label = "Old phone", revoked = true)

        fun device(
            id: String,
            label: String,
            platform: String = "android",
            isThisDevice: Boolean = false,
            revoked: Boolean = false,
            addedBy: String? = null,
        ) = DKeyguardDevice(
            id = id,
            label = label,
            platform = platform,
            fingerprint = "0000 1111 2222 3333",
            addedAt = ADDED_AT,
            addedBy = addedBy,
            isThisDevice = isThisDevice,
            revoked = revoked,
        )

        fun connected(
            transport: DKeyguardTransport,
            upToDate: Boolean,
        ) = DKeyguardPeerStatus.Connection.Connected(
            transport = transport,
            upToDate = upToDate,
        )

        fun peer(
            id: String,
            connection: DKeyguardPeerStatus.Connection,
            lastSeenAt: Instant? = LAST_SEEN_AT,
            lastTransport: DKeyguardTransport? = (connection as? DKeyguardPeerStatus.Connection.Connected)?.transport,
        ) = DKeyguardPeerStatus(
            deviceId = id,
            connection = connection,
            lastSeenAt = lastSeenAt,
            lastTransport = lastTransport,
        )

        val PEERS = mapOf(
            PHONE.id to peer(PHONE.id, connected(DKeyguardTransport.LAN, upToDate = true)),
            REMOVED.id to peer(REMOVED.id, DKeyguardPeerStatus.Connection.Offline),
        )
    }

    private class DevicesHarness(
        testScope: TestScope,
    ) {
        val screen = KeyguardProducerTestScope(testScope)

        val devices = MutableStateFlow(listOf(PHONE, SELF, REMOVED, DESK))
        val peers = MutableStateFlow(PEERS)
        val relay = MutableStateFlow(DKeyguardRelayStatus(DKeyguardRelayStatus.State.CONNECTED))
        val storage = MutableStateFlow<DKeyguardRelayStorageStatus?>(null)
        val canAddDevice = MutableStateFlow(true)

        val revoked = mutableListOf<Triple<P2pGroupId, Set<String>, String>>()
        val left = mutableListOf<Pair<P2pGroupId, String?>>()

        private val useCases = KeyguardDevicesUseCases(
            getKeyguardDevices = object : GetKeyguardDevices {
                override fun invoke(groupId: P2pGroupId) = devices
            },
            getKeyguardSyncStatus = object : GetKeyguardSyncStatus {
                override fun invoke(groupId: P2pGroupId) = combine(relay, peers, storage) { relay, peers, storage ->
                    DKeyguardSyncStatus(relay = relay, peers = peers, storage = storage)
                }
            },
            revokeKeyguardDevice = object : RevokeKeyguardDevice {
                override fun invoke(groupId: P2pGroupId, deviceIds: Set<String>, password: String): IO<Unit> = {
                    revoked += Triple(groupId, deviceIds, password)
                }
            },
            leaveKeyguardGroup = object : LeaveKeyguardGroup {
                override fun invoke(groupId: P2pGroupId, password: String?): IO<Unit> = {
                    left += groupId to password
                }
            },
        )

        private val dateFormatter = object : DateFormatter {
            override fun formatDateTime(instant: Instant): String = instant.toString()

            override fun formatDate(instant: Instant): String = instant.toString()

            override suspend fun formatDateShort(date: LocalDate): String = date.toString()

            override fun formatDateMedium(date: LocalDate): String = date.toString()

            override fun formatTimeShort(time: LocalTime): String = time.toString()
        }

        suspend fun states(): StateFlow<Loadable<KeyguardDevicesState>?> = screen.create()
            .keyguardDevicesStateProducer(
                target = AppLinkDevicesTarget,
                useCases = useCases,
                dateFormatter = dateFormatter,
                confirmationRouteFactory = ConfirmationRouteFactoryDefault,
                confirmAccessByPassword = object : ConfirmAccessByPasswordUseCase {
                    override fun invoke(password: String): IO<Boolean> = { password == PASSWORD }
                },
                getCanAddDevice = { canAddDevice },
            )
            .stateIn(screen.scope, SharingStarted.Eagerly, null)

        /** Answers the last confirmation dialog with [result]. */
        @Suppress("UNCHECKED_CAST")
        fun confirm(result: ConfirmationResult) {
            val intent = assertIs<NavigationIntent.NavigateToRoute>(screen.navigations.last())
            val receiver = intent.route as RouteResultReceiver<ConfirmationResult>
            receiver.resultTransmitter(result)
        }
    }
}
