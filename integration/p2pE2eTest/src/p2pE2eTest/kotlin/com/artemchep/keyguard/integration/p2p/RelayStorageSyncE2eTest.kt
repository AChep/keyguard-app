package com.artemchep.keyguard.integration.p2p

import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DKeyguardRelayStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStorageStatus
import com.artemchep.keyguard.common.service.http.KeyguardHttpUserAgent
import com.artemchep.keyguard.common.service.http.configureKeyguardHttpClient
import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import com.artemchep.keyguard.provider.keyguard.applink.WordlistBlob
import com.artemchep.keyguard.provider.keyguard.crypto.NativeP2pCrypto
import com.artemchep.keyguard.provider.keyguard.transport.GroupObjects
import com.artemchep.keyguard.provider.keyguard.transport.KtorKeyguardRelayConnector
import com.artemchep.keyguard.provider.keyguard.transport.KtorKeyguardRelayMailboxes
import com.artemchep.keyguard.provider.keyguard.transport.RelayEndpoint
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxRef
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxResult
import com.artemchep.keyguard.provider.keyguard.transport.RelayRoom
import com.artemchep.keyguard.provider.keyguard.transport.RelayState
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Apps that are never online together sync through relay storage: each leaves its state
 * on the relay for the other one, sealed for it, and the blobs once for the whole group.
 * The apps take turns: one runs while the other is offline, so nothing but the relay's
 * storage carries the changes. Requests are counted from the metrics of the relay.
 */
class RelayStorageSyncE2eTest {
    @Test
    fun `an edit reaches an app that comes online after the editor went offline`() =
        p2pScenario(storage = RelayServer.Storage()) { testbed ->
            val relay = testbed.relay
            val a = testbed.device("Desktop", Transports.relayOnly(), relayStorage = true)
            val mail = a.addForwarder("Mail")
            val b = testbed.device("Phone", Transports.relayOnly(), relayStorage = true)
            link(member = a, joiner = b)
            awaitConverged(a, b)
            awaitStorage(a, b) { it == DKeyguardRelayStorageStatus.Active }

            b.stopLive()
            val before = relay.awaitUploadsQuiet()
            a.editForwarder(mail, name = "Personal mail")
            val bank = a.addForwarder("Bank")
            relay.awaitUploadsSettled(after = before)
            a.stopLive()

            b.startLive()
            eventually("the phone gets the edits the desktop left on the relay") {
                val snapshot = b.snapshot()
                snapshot.forwarder(mail)?.name == "Personal mail" && snapshot.forwarder(bank)?.name == "Bank"
            }

            a.startLive()
            assertEquals(listOf("Bank", "Personal mail"), awaitConverged(a, b).names())
        }

    @Test
    fun `a wordlist reaches an app on its first visit after the editor went offline`() =
        p2pScenario(storage = RelayServer.Storage()) { testbed ->
            val relay = testbed.relay
            val a = testbed.device("Desktop", Transports.relayOnly(), relayStorage = true)
            val b = testbed.device("Phone", Transports.relayOnly(), relayStorage = true)
            link(member = a, joiner = b)
            awaitConverged(a, b)
            awaitStorage(a, b) { it == DKeyguardRelayStorageStatus.Active }

            // A adds the wordlist while B is offline, and leaves its words on the relay at once.
            b.stopLive()
            val before = relay.awaitUploadsQuiet()
            a.addWordlist(WORDLIST, WORDS)
            eventually("the desktop leaves the words on the relay") { relay.items(KIND_BLOB) > 0L }
            relay.awaitUploadsSettled(after = before)
            a.stopLive()

            // B gets them on its first visit.
            b.startLive()
            eventually("the phone gets the words") { b.wordlists()[WORDLIST]?.sorted() == WORDS.sorted() }
            relay.awaitUploadsQuiet()
            // They stay on the relay for the other devices.
            assertEquals(1L, relay.items(KIND_BLOB))
            assertEquals(0L, relay.requests { it.startsWith("delete/") })

            a.startLive()
            awaitConverged(a, b)
        }

    @Test
    fun `a wordlist added after a removal goes under a new object key`() =
        p2pScenario(storage = RelayServer.Storage()) { testbed ->
            val relay = testbed.relay
            val a = testbed.device("Desktop", Transports.relayOnly(), relayStorage = true)
            val b = testbed.device("Phone", Transports.relayOnly(), relayStorage = true)
            val c = testbed.device("Laptop", Transports.relayOnly(), relayStorage = true)
            link(member = a, joiner = b)
            link(member = a, joiner = c)
            awaitConverged(a, b, c)
            awaitStorage(a, b, c) { it == DKeyguardRelayStorageStatus.Active }
            val old = assertNotNull(a.objectKey())

            // A removes C: the others hold a new object key, which C never gets.
            val cId = c.deviceId()
            a.revoke(cId)
            awaitLinkState(c, DAppLinkState.Removed)
            eventually("the remaining apps hold a new object key") {
                val key = a.objectKey()
                key != null && key != old && key == b.objectKey()
            }
            val new = assertNotNull(a.objectKey())
            assertEquals(listOf(cId), new.covers)
            assertEquals(old, c.objectKey())

            // A adds the wordlist while B is offline, and leaves its words on the relay at once.
            b.stopLive()
            val before = relay.awaitUploadsQuiet()
            a.addWordlist(WORDLIST, WORDS)
            eventually("the desktop leaves the words on the relay") { relay.items(KIND_BLOB) > 0L }
            relay.awaitUploadsSettled(after = before)
            a.stopLive()

            // B gets them on its first visit.
            b.startLive()
            eventually("the phone gets the words") { b.wordlists()[WORDLIST]?.sorted() == WORDS.sorted() }

            // The object is under the new key: the key C holds names nothing.
            val groupKey = assertNotNull(P2pEncoding.base64UrlOrNull(a.membership().groupKeyBase64))
            val hash = BlobExchange.hashOf(NativeP2pCrypto(NativeCryptoGenerator()), WordlistBlob.encode(WORDS))
            val (newId, oldId) = listOf(new, old).map { key ->
                GroupObjects.derive(key, groupKey, SyncScope.APP.roomLabel, NativeCryptoGenerator()).id(hash)
            }
            assertNotEquals(oldId, newId)
            assertEquals(listOf(true, false), relay.holdsBlobs(listOf(newId, oldId)))
        }

    @Test
    fun `an app with relay storage off never takes records from it`() =
        p2pScenario(storage = RelayServer.Storage()) { testbed ->
            val relay = testbed.relay
            val a = testbed.device("Desktop", Transports.relayOnly(), relayStorage = true)
            val mail = a.addForwarder("Mail")
            val b = testbed.device("Phone", Transports.relayOnly())
            link(member = a, joiner = b)
            awaitConverged(a, b)
            // A waits for B to turn it on too.
            awaitStorage(a) { it == DKeyguardRelayStorageStatus.Waiting(listOf("Phone")) }
            awaitStorage(b) { it == DKeyguardRelayStorageStatus.Off }

            b.stopLive()
            a.editForwarder(mail, name = "Personal mail")
            relay.awaitUploadsQuiet()
            a.stopLive()

            // B never had relay storage on: it asks relay storage nothing.
            val requests = relay.requests { true }
            b.startLive()
            awaitRelay(b)
            delay(QUIET)
            assertEquals(requests, relay.requests { true })
            assertEquals("Mail", b.snapshot().forwarder(mail)?.name)

            // Online together, they sync live.
            a.startLive()
            assertEquals("Personal mail", awaitConverged(a, b).forwarder(mail)?.name)
        }

    @Test
    fun `a relay that refuses to store still syncs apps that are online together`() =
        p2pScenario(storage = RelayServer.Storage(minFreeBytes = MORE_THAN_ANY_DISK)) { testbed ->
            val relay = testbed.relay
            val a = testbed.device("Desktop", Transports.relayOnly(), relayStorage = true)
            val mail = a.addForwarder("Mail")
            val b = testbed.device("Phone", Transports.relayOnly(), relayStorage = true)
            link(member = a, joiner = b)
            awaitConverged(a, b)
            awaitStorage(a, b) {
                it == DKeyguardRelayStorageStatus.Active || it is DKeyguardRelayStorageStatus.Paused
            }

            // A has to leave its state for B; the relay is full, and A pauses storage.
            b.stopLive()
            a.editForwarder(mail, name = "Personal mail")
            val paused = awaitStorage(a) { it is DKeyguardRelayStorageStatus.Paused }
            assertIs<DKeyguardRelayStorageStatus.Paused>(paused)
            assertTrue(paused.until > Clock.System.now(), "The pause ends later: ${paused.until}")
            assertTrue(relay.requests("put/storage_full") > 0L)
            assertEquals(0L, relay.requests("put/ok"))
            assertEquals(0L, relay.items(KIND_STATE) + relay.items(KIND_BLOB))

            b.startLive()
            assertEquals("Personal mail", awaitConverged(a, b).forwarder(mail)?.name)
        }

    @Test
    fun `items outlive a restart of the relay`() =
        p2pScenario(storage = RelayServer.Storage()) { testbed ->
            val relay = testbed.relay
            val a = testbed.device("Desktop", Transports.relayOnly(), relayStorage = true)
            val mail = a.addForwarder("Mail")
            val b = testbed.device("Phone", Transports.relayOnly(), relayStorage = true)
            link(member = a, joiner = b)
            awaitConverged(a, b)
            awaitStorage(a, b) { it == DKeyguardRelayStorageStatus.Active }

            b.stopLive()
            val before = relay.awaitUploadsQuiet()
            a.editForwarder(mail, name = "Personal mail")
            relay.awaitUploadsSettled(after = before)
            a.stopLive()

            relay.restart()
            // The new process restored the items from the directory.
            assertTrue(relay.items(KIND_STATE) > 0L)

            b.startLive()
            eventually("the phone gets the edit the relay kept over its restart") {
                b.snapshot().forwarder(mail)?.name == "Personal mail"
            }
        }

    private companion object {
        const val WORDLIST = "Animals"
        val WORDS = listOf("otter", "lynx", "heron", "badger", "marten")

        const val KIND_STATE = "state"
        const val KIND_BLOB = "blob"

        /** The most the relay accepts for the free space it leaves: more than any disk has. */
        const val MORE_THAN_ANY_DISK = 1L shl 50
    }
}

/** Whether the relay holds the blob object of each of [ids], asked over a connection of its own. */
private suspend fun RelayServer.holdsBlobs(ids: List<String>): List<Boolean> = coroutineScope {
    val endpoint = RelayEndpoint(url, token = token)
    val http = HttpClient(OkHttp) {
        configureKeyguardHttpClient(createApplicationJson(), KeyguardHttpUserAgent.Curl)
    }
    // The relay serves items only to a joined connection, by its ticket.
    val room = RelayRoom.forInvite(NativeCryptoGenerator().seed(length = 32), NativeCryptoGenerator())
    val connection = KtorKeyguardRelayConnector(http).open(endpoint, room)
    val joined = launch { connection.run() }
    try {
        val ticket = withTimeout(STORAGE_TIMEOUT) {
            connection.client.state
                .filterIsInstance<RelayState.Joined>()
                .first { it.ticket != null }
                .ticket
        }
        val mailbox = KtorKeyguardRelayMailboxes(http).get(endpoint)
        ids.map { id ->
            mailbox.head(RelayMailboxRef(checkNotNull(ticket), MailboxKind.BLOB, id)) is RelayMailboxResult.Found
        }
    } finally {
        joined.cancel()
        http.close()
    }
}

/** Waits until the session of [device] joined the relay. */
private suspend fun awaitRelay(device: P2pE2eDevice) {
    withTimeoutOrNull(STORAGE_TIMEOUT) {
        device.status()
            .filterNotNull()
            .first { it.relay == DKeyguardRelayStatus.State.CONNECTED }
    } ?: fail("${device.name} did not join the relay in $STORAGE_TIMEOUT")
}

/** Waits until relay storage of each of [devices] is in a status that matches [predicate]; returns the last one's. */
private suspend fun awaitStorage(
    vararg devices: P2pE2eDevice,
    predicate: (DKeyguardRelayStorageStatus) -> Boolean,
): DKeyguardRelayStorageStatus = devices
    .map { device ->
        var last: DKeyguardRelayStorageStatus? = null
        withTimeoutOrNull(STORAGE_TIMEOUT) {
            device.status()
                .filterNotNull()
                .first { status ->
                    last = status.storage
                    predicate(status.storage)
                }
                .storage
        } ?: fail("The relay storage of ${device.name} did not reach the status in $STORAGE_TIMEOUT; it is $last")
    }
    .last()

private suspend fun RelayServer.requests(
    predicate: (String) -> Boolean,
): Long = withContext(Dispatchers.IO) {
    mailboxRequests()
        .filterKeys(predicate)
        .values
        .sum()
}

/** The requests with the `op/result`, for example `put/ok`. */
private suspend fun RelayServer.requests(key: String): Long = requests { it == key }

private suspend fun RelayServer.items(kind: String): Long = withContext(Dispatchers.IO) {
    mailboxItems()[kind] ?: 0L
}

/**
 * Waits until the relay took no upload for a while: the devices left what they had to.
 * Returns the uploads so far.
 */
private suspend fun RelayServer.awaitUploadsQuiet(): Long {
    var last = requests(UPLOADS)
    var quietSince = TimeSource.Monotonic.markNow()
    withTimeoutOrNull(STORAGE_TIMEOUT) {
        while (quietSince.elapsedNow() < QUIET) {
            delay(POLL_MS)
            val now = requests(UPLOADS)
            if (now != last) {
                last = now
                quietSince = TimeSource.Monotonic.markNow()
            }
        }
    } ?: fail("The devices kept uploading for $STORAGE_TIMEOUT")
    return last
}

/** Waits until the relay took an upload past the [after] first ones, then none for a while. */
private suspend fun RelayServer.awaitUploadsSettled(after: Long): Long {
    eventually("a device leaves its state on the relay") { requests(UPLOADS) > after }
    return awaitUploadsQuiet()
}

private const val UPLOADS = "put/ok"

private val STORAGE_TIMEOUT = 60.seconds

private const val POLL_MS = 200L

/**
 * How long no upload must come for the devices to have left what they had to, and how
 * long nothing must arrive: longer than the debounce of a session and of a post.
 */
private val QUIET = 3.seconds
