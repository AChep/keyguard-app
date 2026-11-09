package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DKeyguardRelayStorageStatus
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.keyvalue.impl.SqlDelightVaultSettingsKeyValueStore
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxAddress
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxCodec
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxOpening
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxState
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.DeviceFeature
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import com.artemchep.keyguard.provider.bitwarden.usecase.PutKeyguardNetworkSettingsImpl
import com.artemchep.keyguard.provider.keyguard.P2pMembershipStore
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkBlobs
import com.artemchep.keyguard.provider.keyguard.applink.WordlistBlob
import com.artemchep.keyguard.provider.keyguard.transport.GroupObjects
import com.artemchep.keyguard.provider.keyguard.transport.MailboxKeys
import com.artemchep.keyguard.provider.keyguard.transport.RelayLimits
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxLimits
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxError
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxResult
import com.artemchep.keyguard.provider.keyguard.transport.TestRelay
import com.artemchep.keyguard.provider.keyguard.transport.TestRelayMailbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Relay storage between devices that are never online at the same time: each one syncs
 * once, as Android's background sync does, and the relay keeps the state of each device
 * for the other one.
 */
// One function per scenario, with the helpers they share.
@Suppress("TooManyFunctions", "LargeClass")
class KeyguardMailboxTest {
    private val timings = KeyguardMailboxTimings(
        fetchIntervalMs = 500L,
        postDebounceMs = 200L,
        recheckMs = 500L,
        maxBackoffMs = 1_000L,
        settleMs = 10_000L,
    )

    private fun group(
        mailbox: TestRelayMailbox,
        relayStorage: Boolean = true,
        size: Int = 2,
        mailboxTimings: KeyguardMailboxTimings = timings,
        relay: TestRelay = TestRelay(mailbox = mailbox),
        blobRetryDelayMs: Long = 100L,
    ) = KeyguardSessionTestGroup(
        size = size,
        relay = relay,
        relayStorage = relayStorage,
        mailboxTimings = mailboxTimings,
        blobRetryDelayMs = blobRetryDelayMs,
    )

    /** Turns relay storage of the [device] on or off, as its user does. */
    private suspend fun allow(device: SessionTestDevice, value: Boolean) {
        PutKeyguardNetworkSettingsImpl(P2pMembershipStore(device.dbManager))(
            P2pGroupId.AppLink,
            device.membership.network.copy(relayStorage = value),
        ).bind()
    }

    private fun key(base64: String) = checkNotNull(P2pEncoding.base64UrlOrNull(base64))

    /** The object key [device] holds now. */
    private fun objectKeyOf(device: SessionTestDevice): ObjectKey {
        val key = ObjectKey.fromEntry(device.app.entries()[ObjectKey.ENTRY_KEY])
        return assertNotNull(key, "No object key on ${device.deviceId}")
    }

    /** The keys of the objects of the group of [device], under the object key it holds now. */
    private fun objectsOf(device: SessionTestDevice): GroupObjects = testRoomConnector(TestRelay().connector)
        .groupObjects(objectKeyOf(device), key(device.membership.groupKeyBase64), SyncScope.APP)

    /** The blobs [device] stored as settled under the object key [keyId], see [KeyguardRelayStorageUse]. */
    private suspend fun settledOf(device: SessionTestDevice, keyId: String): Set<String>? =
        VaultKeyguardRelayStorageUse(SqlDelightVaultSettingsKeyValueStore(device.dbManager, Dispatchers.Unconfined))
            .settledBlobs(P2pGroupId.APP_LINK_ID)
            ?.takeIf { it.keyId == keyId }
            ?.hashes

    /**
     * The keys of the items [from] leaves for [to], as [to] derives them: only [to] opens
     * them, with its ML-KEM private key.
     */
    private fun inbox(from: SessionTestDevice, to: SessionTestDevice): MailboxKeys {
        val room = testRoomConnector(TestRelay().connector).pairRoom(
            groupKey = key(to.membership.groupKeyBase64),
            scope = SyncScope.APP,
            localNoisePrivate = key(to.membership.device.noisePrivateKeyBase64),
            localNoisePub = key(to.membership.device.noisePublicKeyBase64),
            peerNoisePub = key(from.membership.device.noisePublicKeyBase64),
            localKemPrivate = key(to.membership.device.kemPrivateKeyBase64),
            peerKemPub = key(from.membership.device.kemPublicKeyBase64),
        )
        return checkNotNull(room.mailbox).inbox
    }

    /** Opens the state item [from] left for [to] on the relay, as [to] does. */
    private fun stateOf(mailbox: TestRelayMailbox, from: SessionTestDevice, to: SessionTestDevice): MailboxState {
        val keys = inbox(from, to)
        val item = assertNotNull(mailbox.items(MailboxKind.STATE)[keys.stateId], "No state of ${from.deviceId}")
        return assertNotNull(openState(item.body, from, to))
    }

    /** Opens the sealed state item [body] that [from] left for [to], as [to] does; `null` if it does not open. */
    private fun openState(body: ByteArray, from: SessionTestDevice, to: SessionTestDevice): MailboxState? {
        val keys = inbox(from, to)
        val plaintext = keys.open(body, maxSize = MailboxCodec.maxStateSize().toLong())
            ?: return null
        val opening = MailboxCodec.decodeState(
            bytes = plaintext,
            expected = MailboxAddress(item = keys.stateId, from = from.deviceId, to = to.deviceId),
            nowMs = System.currentTimeMillis(),
            hasher = from.crypto::sha256,
        )
        return (opening as? MailboxOpening.Valid<MailboxState>)?.item
    }

    /** The state [from] left for [to] on the relay, or `null` if there is none that opens. */
    private fun stateOrNull(mailbox: TestRelayMailbox, from: SessionTestDevice, to: SessionTestDevice) =
        runCatching { stateOf(mailbox, from, to) }.getOrNull()

    /** The id of the object key of the state [from] left for [to] on the relay, if any. */
    private fun stateKeyIdOf(mailbox: TestRelayMailbox, from: SessionTestDevice, to: SessionTestDevice): String? =
        stateOrNull(mailbox, from, to)
            ?.envelope
            ?.envelope
            ?.entries
            ?.firstOrNull { it.key == ObjectKey.ENTRY_KEY }
            ?.let(ObjectKey::fromEntry)
            ?.keyId(from.crypto::sha256)

    /** The number of entries of the state [from] left for [to] on the relay. */
    private fun entriesOf(mailbox: TestRelayMailbox, from: SessionTestDevice, to: SessionTestDevice): Int =
        stateOrNull(mailbox, from, to)?.envelope?.envelope?.entries?.size ?: 0

    /** The blob GETs the relay served so far. */
    private fun blobGets(mailbox: TestRelayMailbox): Int =
        mailbox.requests.count { it.kind == MailboxKind.BLOB && it.method == "GET" }

    /** The words of the wordlist [name] on [device], sorted, as the blob holds them. */
    private suspend fun wordsOf(device: SessionTestDevice, name: String): List<String>? =
        device.wordlists()[name]?.sorted()

    /**
     * Runs a visit of [device]: its session runs, as in the foreground, while [block] runs;
     * then the app goes offline, and its session ends. The session holds the membership
     * the device stores now, as the app does: its user may have changed its settings.
     */
    private suspend fun visit(device: SessionTestDevice, block: suspend () -> Unit) {
        val membership = checkNotNull(device.app.service.membership())
        val live = device.scope.launch { device.registry.hold(P2pGroupId.APP_LINK_ID, membership) }
        block()
        live.cancel()
        eventually { device.registry.get(P2pGroupId.APP_LINK_ID) == null }
    }

    /** A visit of [device] in which it adds the wordlist [name], and leaves it for [peer]. */
    private suspend fun addOnVisit(
        mailbox: TestRelayMailbox,
        device: SessionTestDevice,
        peer: SessionTestDevice,
        name: String,
        words: List<String>,
    ) = visit(device) {
        // Its session runs before the wordlist is added.
        eventually { stateOrNull(mailbox, device, peer) != null }
        val entries = entriesOf(mailbox, device, peer)
        device.addWordlist(name, words)
        eventually { entriesOf(mailbox, device, peer) > entries && mailbox.items(MailboxKind.BLOB).isNotEmpty() }
    }

    @Test
    fun `a device gets the state the other one left while it was offline`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox).use { group ->
            val (a, b) = group.devices
            a.addForwarder("Left on the relay")
            // B is offline: the state of A waits on the relay.
            a.sync()
            assertEquals(1, mailbox.items(MailboxKind.STATE).size)

            // A is gone by the time B comes online.
            b.sync()
            eventually { "Left on the relay" in b.forwarders() }
            // B left its own state for A too.
            assertEquals(2, mailbox.items(MailboxKind.STATE).size)
        }
    }

    @Test
    fun `a device that never had relay storage on makes no request to it`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox, relayStorage = false).use { group ->
            val (a, b) = group.devices
            a.addForwarder("Live only")
            repeat(2) {
                a.sync()
                b.sync()
            }
            assertTrue(mailbox.requests.isEmpty())
            assertFalse("Live only" in b.forwarders())
        }
    }

    @Test
    fun `a wordlist reaches a device on its next visit, from one upload`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox).use { group ->
            val (a, b) = group.devices
            val words = listOf("alpha", "beta", "gamma")
            // A adds the wordlist while B is offline, and leaves its words at once.
            addOnVisit(mailbox, a, b, "Greek", words)

            visit(b) { eventually { wordsOf(b, "Greek") == words } }
            assertEquals(1, mailbox.puts(MailboxKind.BLOB))
            // The words stay for the other devices.
            assertEquals(1, mailbox.items(MailboxKind.BLOB).size)
            assertTrue(mailbox.requests.none { it.kind == MailboxKind.BLOB && it.method == "DELETE" })
        }
    }

    @Test
    fun `a one-shot sync gets the words of a wordlist it learns of in the same call`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox).use { group ->
            val (a, b) = group.devices
            val words = listOf("first", "second")
            addOnVisit(mailbox, a, b, "Ordinals", words)

            // B learns of the wordlist from the state A left: the words come before the call returns.
            b.sync()
            assertEquals(words, wordsOf(b, "Ordinals"))
            assertEquals(1, blobGets(mailbox))
        }
    }

    @Test
    fun `every device gets a wordlist from one upload`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox, size = 3).use { group ->
            val (a, b, c) = group.devices
            val words = listOf("one", "three", "two")
            addOnVisit(mailbox, a, b, "Numbers", words)

            visit(b) { eventually { wordsOf(b, "Numbers") == words } }
            visit(c) { eventually { wordsOf(c, "Numbers") == words } }
            assertEquals(1, mailbox.puts(MailboxKind.BLOB))
            val objects = objectsOf(a)
            val hash = mailbox.items(MailboxKind.BLOB).keys.single()
            // Every device names the object alike.
            assertEquals(hash, objectsOf(c).id(BlobExchange.hashOf(c.crypto, WordlistBlob.encode(words))))
            assertEquals(objects.id(BlobExchange.hashOf(a.crypto, WordlistBlob.encode(words))), hash)
        }
    }

    @Test
    fun `a blob added while offline is left on the next visit`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox).use { group ->
            val (a, b) = group.devices
            visit(a) { eventually { stateOrNull(mailbox, a, b) != null } }
            val words = listOf("left", "right")
            a.addWordlist("Sides", words)

            // A leaves the words on its own, as no peer can miss them yet.
            visit(a) { eventually { mailbox.items(MailboxKind.BLOB).isNotEmpty() } }
            visit(b) { eventually { wordsOf(b, "Sides") == words } }
            assertEquals(1, mailbox.puts(MailboxKind.BLOB))
            // B never had to name the words missing.
            assertEquals(emptyList(), stateOf(mailbox, b, a).missing)
        }
    }

    @Test
    fun `an object the relay evicted is left again once a device misses it`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox).use { group ->
            val (a, b) = group.devices
            val words = listOf("green", "red")
            addOnVisit(mailbox, a, b, "Colors", words)
            mailbox.evict(MailboxKind.BLOB)

            // B learns of the wordlist, finds no words on the relay, and says it misses them.
            visit(b) { eventually { stateOrNull(mailbox, b, a)?.missing?.isNotEmpty() == true } }
            assertNull(b.wordlists()["Colors"])

            // A leaves them again on its next visit, and B gets them on its next one.
            visit(a) { eventually { mailbox.items(MailboxKind.BLOB).isNotEmpty() } }
            visit(b) {
                eventually { wordsOf(b, "Colors") == words }
                // B misses nothing any longer.
                eventually { stateOrNull(mailbox, b, a)?.missing?.isEmpty() == true }
            }
            assertEquals(2, mailbox.puts(MailboxKind.BLOB))
        }
    }

    @Test
    fun `an object that does not open is deleted, missed, and left again`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox).use { group ->
            val (a, b) = group.devices
            val words = listOf("broken", "mended")
            addOnVisit(mailbox, a, b, "Repair", words)
            val hash = BlobExchange.hashOf(a.crypto, WordlistBlob.encode(words))
            val id = mailbox.items(MailboxKind.BLOB).keys.single()
            // Garbage in the format of this build under the id of the object, as a device
            // that knows the id could write.
            val garbage = ByteArray(256) { it.toByte() }
                .also { it[0] = CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte }
            mailbox.replace(MailboxKind.BLOB, id, garbage)

            // B deletes the object it can not open, and says it misses the words.
            visit(b) { eventually { stateOrNull(mailbox, b, a)?.missing == listOf(hash) } }
            assertNull(b.wordlists()["Repair"])
            assertTrue(mailbox.items(MailboxKind.BLOB).isEmpty())
            assertEquals(1, mailbox.requests.count { it.kind == MailboxKind.BLOB && it.method == "DELETE" })

            // A leaves them again on its next visit, and B gets them on its next one.
            visit(a) { eventually { id in mailbox.items(MailboxKind.BLOB) } }
            visit(b) { eventually { wordsOf(b, "Repair") == words } }
            assertEquals(2, mailbox.puts(MailboxKind.BLOB))
        }
    }

    @Test
    fun `an object of a format this build does not read, or too large for it, is kept`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox).use { group ->
            val (a, b) = group.devices
            val words = listOf("newer", "unread")
            addOnVisit(mailbox, a, b, "Newer", words)
            val hash = BlobExchange.hashOf(a.crypto, WordlistBlob.encode(words))
            val id = mailbox.items(MailboxKind.BLOB).keys.single()
            val bodies = listOf(
                // Sealed with a cipher this build does not know, as a newer build may.
                mailbox.items(MailboxKind.BLOB).getValue(id).body.copyOf().also { it[0] = 0x7F },
                // Sealed with the object key, but larger than this build takes once opened.
                objectsOf(a).seal(hash, MailboxCodec.encodeBlob(ByteArray(AppLinkBlobs.MAX_BLOB_SIZE.toInt() + 1))),
            )
            bodies.forEach { body ->
                mailbox.replace(MailboxKind.BLOB, id, body)
                val gets = blobGets(mailbox)
                // B reads neither, so it misses the words, but leaves the object for the others.
                visit(b) {
                    eventually { blobGets(mailbox) > gets }
                    delay(500L)
                }
                assertNull(b.wordlists()["Newer"])
                assertTrue(id in mailbox.items(MailboxKind.BLOB))
            }
            assertTrue(mailbox.requests.none { it.kind == MailboxKind.BLOB && it.method == "DELETE" })
        }
    }

    @Test
    fun `a new object key names the objects anew, and the blobs held then are left once missed`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox, size = 3).use { group ->
            val (a, b, c) = group.devices
            val words = listOf("east", "west")
            addOnVisit(mailbox, a, b, "Winds", words)
            val hash = BlobExchange.hashOf(a.crypto, WordlistBlob.encode(words))
            val before = objectsOf(a)
            assertEquals(setOf(before.id(hash)), mailbox.items(MailboxKind.BLOB).keys)

            // A removes C while its session runs: its pass writes a new object key, and the
            // objects start over under it. The blob A holds counts as left, so it is not
            // left again on its own.
            visit(a) {
                group.revoke(c)
                eventually { objectKeyOf(a).keyId(a.crypto::sha256) != before.keyId }
                eventually { settledOf(a, objectsOf(a).keyId) == setOf(hash) }
                delay(1_000L)
            }
            val after = objectsOf(a)
            assertEquals(listOf(c.deviceId), objectKeyOf(a).covers)
            assertNotEquals(before.id(hash), after.id(hash))
            assertEquals(1, mailbox.puts(MailboxKind.BLOB))
            assertEquals(setOf(before.id(hash)), mailbox.items(MailboxKind.BLOB).keys)

            // The object under the old key is gone too. B adopts the new key, finds no
            // words, and says it misses them.
            mailbox.evict(MailboxKind.BLOB)
            visit(b) { eventually { stateOrNull(mailbox, b, a)?.missing == listOf(hash) } }
            assertEquals(after.keyId, objectsOf(b).keyId)
            assertNull(b.wordlists()["Winds"])

            // A leaves them under the new key on its next visit, and B gets them on its next one.
            visit(a) { eventually { after.id(hash) in mailbox.items(MailboxKind.BLOB) } }
            visit(b) { eventually { wordsOf(b, "Winds") == words } }
            assertEquals(2, mailbox.puts(MailboxKind.BLOB))
        }
    }

    @Test
    fun `a removed device never gets a state item with the new object key`() = realTime {
        val mailbox = TestRelayMailbox()
        // Each change is posted at once.
        group(mailbox, size = 3, mailboxTimings = timings.copy(postDebounceMs = 0L)).use { group ->
            val (a, _, c) = group.devices
            val toC = inbox(a, c).stateId
            val live = a.startLive()
            eventually { stateOrNull(mailbox, a, c) != null }
            val before = objectKeyOf(a)

            // A removes C while its session runs: its pass writes a new object key.
            group.revoke(c)
            eventually { !objectKeyOf(a).key.contentEquals(before.key) }
            val after = objectKeyOf(a)
            // Past a few rounds of the worker of C, had it stayed.
            delay(2_000L)
            live.cancel()

            val sent = mailbox.putBodies
                .filter { (item, _) -> item == toC }
                .mapNotNull { (_, body) -> openState(body, a, c) }
                .mapNotNull { state -> state.envelope?.envelope?.entries }
                .mapNotNull { entries -> entries.firstOrNull { it.key == ObjectKey.ENTRY_KEY } }
                .mapNotNull(ObjectKey::fromEntry)
            assertTrue(sent.isNotEmpty())
            assertTrue(sent.none { it.key.contentEquals(after.key) })
        }
    }

    @Test
    fun `a blob still to leave when a new object key comes is left under the new key`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox, size = 3).use { group ->
            val (a, b, c) = group.devices
            visit(a) { eventually { stateOrNull(mailbox, a, b) != null } }
            val before = objectsOf(a)
            val words = listOf("north", "south")
            a.addWordlist("Poles", words)
            val hash = BlobExchange.hashOf(a.crypto, WordlistBlob.encode(words))

            // A removes C while offline: the new key comes while the words still wait to be left.
            group.revoke(c)
            eventually { objectKeyOf(a).keyId(a.crypto::sha256) != before.keyId }
            val after = objectsOf(a)

            // A leaves them under the new key on its own; B never has to name them missing.
            visit(a) { eventually { after.id(hash) in mailbox.items(MailboxKind.BLOB) } }
            visit(b) { eventually { wordsOf(b, "Poles") == words } }
            assertEquals(1, mailbox.puts(MailboxKind.BLOB))
            assertEquals(emptyList(), stateOf(mailbox, b, a).missing)
        }
    }

    /**
     * B learns of a wordlist of A whose words the relay lacks under the object key, and
     * misses them. Then, while B is offline, A removes C and leaves the words under the new
     * key alone. Returns the words.
     */
    private suspend fun missThenRekey(mailbox: TestRelayMailbox, group: KeyguardSessionTestGroup): List<String> {
        val (a, b, c) = group.devices
        val words = listOf("east", "west")
        addOnVisit(mailbox, a, b, "Winds", words)
        val hash = BlobExchange.hashOf(a.crypto, WordlistBlob.encode(words))
        val before = objectsOf(a)
        mailbox.evict(MailboxKind.BLOB)
        visit(b) { eventually { stateOrNull(mailbox, b, a)?.missing == listOf(hash) } }

        group.revoke(c)
        eventually { objectKeyOf(a).keyId(a.crypto::sha256) != before.keyId }
        val after = objectsOf(a)
        visit(a) {
            eventually {
                after.id(hash) in mailbox.items(MailboxKind.BLOB) && stateKeyIdOf(mailbox, a, b) == after.keyId
            }
        }
        assertEquals(setOf(after.id(hash)), mailbox.items(MailboxKind.BLOB).keys)
        assertEquals(before.keyId, objectsOf(b).keyId)
        assertNull(b.wordlists()["Winds"])
        return words
    }

    @Test
    fun `a blob the relay lacked under the old object key is fetched at once under the new one`() = realTime {
        val mailbox = TestRelayMailbox()
        // A blob the relay lacked is passed over for longer than the test waits.
        group(mailbox, size = 3, blobRetryDelayMs = 10L * 60L * 1_000L).use { group ->
            val (_, b) = group.devices
            val words = missThenRekey(mailbox, group)

            // B adopts the new key from the state A left, and fetches the words under it.
            visit(b) { eventually { wordsOf(b, "Winds") == words } }
        }
    }

    @Test
    fun `a one-shot sync that learns a new object key gets the words it misses in the same call`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox, size = 3, blobRetryDelayMs = 10L * 60L * 1_000L).use { group ->
            val (_, b) = group.devices
            val words = missThenRekey(mailbox, group)

            // B adopts the new key from the state A left: the words come before the call returns.
            b.sync()
            assertEquals(words, wordsOf(b, "Winds"))
        }
    }

    @Test
    fun `without an object key, a device makes no blob request, and a one-shot exchange does not wait`() = realTime {
        val mailbox = TestRelayMailbox()
        // B runs a build that knows no object key: the group has none.
        val older = DeviceCaps.current(relayStorage = true)
            .let { caps -> caps.copy(kinds = caps.kinds - EntryKind.OBJECT_KEY.wireName) }
        val group = KeyguardSessionTestGroup(
            size = 2,
            relay = TestRelay(mailbox = mailbox),
            relayStorage = true,
            mailboxTimings = timings.copy(settleMs = 30_000L),
            blobRetryDelayMs = 100L,
            caps = older,
            objectKey = false,
        )
        group.use {
            val (a, b) = group.devices
            val started = TimeSource.Monotonic.markNow()
            a.sync()
            // The objects have no round to wait for.
            assertTrue(started.elapsedNow() < 15.seconds, "The exchange took ${started.elapsedNow()}")
            assertNull(ObjectKey.fromEntry(a.app.entries()[ObjectKey.ENTRY_KEY]))

            // A adds a wordlist while its session runs, and leaves its state for B, but no words.
            visit(a) {
                a.addWordlist("Fresh", listOf("three"))
                eventually { entriesOf(mailbox, a, b) > 0 }
                delay(2_000L)
            }
            assertTrue(mailbox.requests.none { it.kind == MailboxKind.BLOB })
        }
    }

    @Test
    fun `an upload that finds the object there already counts as done`() = realTime {
        val mailbox = TestRelayMailbox()
        // Another device writes each object just before this one does.
        mailbox.raceBlobPuts = true
        group(mailbox).use { group ->
            val (a, b) = group.devices
            val words = listOf("north", "south")
            visit(a) {
                eventually { stateOrNull(mailbox, a, b) != null }
                a.addWordlist("Compass", words)
                eventually { mailbox.exists == 1 }
                // Past the longest back-off and a few rounds: the object is neither looked up
                // again, as after a failure, nor written again.
                delay(2_000L)
            }
            assertEquals(1, mailbox.puts(MailboxKind.BLOB))
            assertEquals(1, mailbox.requests.count { it.kind == MailboxKind.BLOB && it.method == "HEAD" })

            // The object the other device wrote is the same.
            visit(b) { eventually { wordsOf(b, "Compass") == words } }
        }
    }

    @Test
    fun `a fetch cut short resumes on the next visit from the bytes it got`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox).use { group ->
            val (a, b) = group.devices
            val words = List(500) { "word$it" }.sorted()
            addOnVisit(mailbox, a, b, "Large", words)
            val length = mailbox.items(MailboxKind.BLOB).values.single().body.size
            val cut = 100
            assertTrue(length > cut, "The object is $length bytes")

            // The link of B stalls a little into the object, and its visit ends.
            mailbox.stallBlobGetsAfter = cut
            visit(b) { eventually { mailbox.stalls > 0 } }
            assertNull(b.wordlists()["Large"])
            assertEquals(emptyList(), mailbox.resumes)

            // The next visit asks for the rest only.
            mailbox.stallBlobGetsAfter = null
            visit(b) { eventually { wordsOf(b, "Large") == words } }
            assertEquals(listOf(cut.toLong()), mailbox.resumes)
            assertEquals(1, mailbox.stalls)
        }
    }

    @Test
    fun `a device that turned relay storage off cleans up once, then makes no request`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox).use { group ->
            val (a, b) = group.devices
            addOnVisit(mailbox, a, b, "Kept", listOf("one"))
            b.sync()
            // The words arrived in that pass, and queued one more: it runs first, so B posts
            // nothing once A turned relay storage off.
            b.sync()
            assertEquals(2, mailbox.items(MailboxKind.STATE).size)

            allow(a, false)
            a.sync()
            // The state of B for A and the object are gone; the state of A holds its roster only.
            eventually { mailbox.items(MailboxKind.BLOB).isEmpty() }
            assertEquals(1, mailbox.items(MailboxKind.STATE).size)
            assertNull(stateOf(mailbox, a, b).envelope)
            val requests = mailbox.requests.size

            // The relay holds nothing of A any longer: A asks it nothing.
            repeat(2) { a.sync() }
            assertEquals(requests, mailbox.requests.size)
        }
    }

    @Test
    fun `a device that turns relay storage off deletes what it left and tells the others`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox).use { group ->
            val (a, b) = group.devices
            a.sync()
            b.sync()
            assertEquals(2, mailbox.items(MailboxKind.STATE).size)
            val stored = mailbox.items(MailboxKind.STATE)

            PutKeyguardNetworkSettingsImpl(P2pMembershipStore(a.dbManager))(
                P2pGroupId.AppLink,
                a.membership.network.copy(relayStorage = false),
            ).bind()
            a.sync()
            // The state of B for A is gone; the state of A holds its roster only.
            val after = mailbox.items(MailboxKind.STATE)
            assertEquals(1, after.size)
            val (id, notice) = after.entries.single()
            assertTrue(id in stored)
            assertTrue(notice.tag != stored.getValue(id).tag)

            // B learns that A turned it off, and stops storing: it leaves its roster only.
            b.addForwarder("Not stored")
            b.sync()
            val record = b.roster().devices.single { it.id == a.deviceId }
            assertFalse(DeviceFeature.RELAY_STORAGE in record.caps.features)
            assertNull(stateOf(mailbox, a, b).envelope)
            assertNull(stateOf(mailbox, b, a).envelope)
            assertTrue(mailbox.items(MailboxKind.BLOB).isEmpty())
        }
    }

    @Test
    fun `devices that turn relay storage on while apart open it without a live session`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox, relayStorage = false).use { group ->
            val (a, b) = group.devices
            allow(a, true)
            a.addForwarder("Edited on A")
            // B does not allow it yet: A leaves its roster only.
            a.sync()
            assertNull(stateOf(mailbox, a, b).envelope)

            // B allows it, and learns from that roster that A does too.
            allow(b, true)
            b.sync()
            assertFalse("Edited on A" in b.forwarders())
            assertNotNull(stateOf(mailbox, b, a).envelope)

            // A learns that B allows it: the whole state goes out.
            a.sync()
            assertNotNull(stateOf(mailbox, a, b).envelope)
            b.sync()
            eventually { "Edited on A" in b.forwarders() }
        }
    }

    @Test
    fun `while one device has relay storage off, the others leave their rosters only`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox, relayStorage = false, size = 3).use { group ->
            val (a, b, c) = group.devices
            allow(a, true)
            allow(b, true)
            a.addWordlist("Kept", listOf("one", "two"))
            a.addForwarder("Kept on A")
            a.sync()
            b.sync()
            a.sync()
            // A and B know that both allow it, but C does not.
            val record = a.roster().devices.single { it.id == b.deviceId }
            assertTrue(DeviceFeature.RELAY_STORAGE in record.caps.features)
            listOf(b, c).forEach { peer ->
                val state = stateOf(mailbox, a, peer)
                assertNull(state.envelope)
                assertTrue(state.missing.isEmpty())
            }
            assertFalse("Kept on A" in b.forwarders())
            // A adds a wordlist while its session runs: the gate is closed, so it leaves no words.
            visit(a) {
                a.addWordlist("Fresh", listOf("three"))
                delay(2_000L)
            }
            assertTrue(mailbox.items(MailboxKind.BLOB).isEmpty())
            assertTrue(mailbox.requests.none { it.kind == MailboxKind.BLOB })
            // C has it off, and never had it on: it asks the relay nothing.
            val requests = mailbox.requests.size
            c.sync()
            assertEquals(requests, mailbox.requests.size)
            assertFalse("Kept on A" in c.forwarders())
        }
    }

    @Test
    fun `an item the relay evicted is posted again, and its absence is no failure`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox).use { group ->
            val (a, b) = group.devices
            a.addForwarder("Evicted")
            a.sync()
            mailbox.evictAll()

            b.sync()
            assertFalse("Evicted" in b.forwarders())

            a.sync()
            b.sync()
            eventually { "Evicted" in b.forwarders() }
        }
    }

    @Test
    fun `an item the relay drops while its sender stays connected is posted again`() = realTime {
        val mailbox = TestRelayMailbox()
        group(mailbox, mailboxTimings = timings.copy(relayRecheckMs = 1_000L)).use { group ->
            val (a, _) = group.devices
            val live = a.startLive()
            eventually { mailbox.items(MailboxKind.STATE).isNotEmpty() }
            val puts = mailbox.puts(MailboxKind.STATE)

            // Trimmed, say, while A stays connected and changes nothing.
            mailbox.evictAll()
            eventually { mailbox.items(MailboxKind.STATE).isNotEmpty() }
            assertTrue(mailbox.puts(MailboxKind.STATE) > puts)
            live.cancel()
        }
    }

    @Test
    fun `an item about to expire is posted again while its sender keeps it`() = realTime {
        val limits = RelayMailboxLimits(ttlSeconds = 4L)
        val mailbox = TestRelayMailbox(limits)
        val relay = TestRelay(limits = RelayLimits(mailbox = limits), mailbox = mailbox)
        group(mailbox, relay = relay).use { group ->
            val (a, _) = group.devices
            val live = a.startLive()
            eventually { mailbox.items(MailboxKind.STATE).isNotEmpty() }
            val puts = mailbox.puts(MailboxKind.STATE)
            // Each one lives 4 seconds; the same state replaces it before it expires.
            repeat(8) {
                delay(1_000L)
                assertTrue(mailbox.items(MailboxKind.STATE).isNotEmpty())
            }
            assertTrue(mailbox.puts(MailboxKind.STATE) > puts + 1)
            live.cancel()
        }
    }

    @Test
    fun `a pause that comes while a worker backs off holds its next request`() = realTime {
        val mailbox = TestRelayMailbox()
        // The first state GET fails, and its worker backs off for about a second.
        mailbox.stateGetAnswers += RelayMailboxResult.Failed(RelayMailboxError.NETWORK)
        group(mailbox, size = 3).use { group ->
            val a = group.devices.first()
            val live = a.startLive()
            eventually { mailbox.stateGetAnswers.isEmpty() }
            // Meanwhile the worker of the other peer asks again, and the relay pauses every
            // worker for 4 seconds.
            val pause = RelayMailboxResult.RetryLater(RelayMailboxError.STORAGE_FULL, retryAfterSeconds = 4)
            mailbox.stateGetAnswers += pause
            eventually { mailbox.stateGetAnswers.isEmpty() }
            val requests = mailbox.requests.size

            // The back-off ends within the pause: no request goes out until the pause does.
            delay(2_500L)
            assertEquals(requests, mailbox.requests.size)
            eventually { mailbox.requests.size > requests }
            live.cancel()
        }
    }

    @Test
    fun `a full relay pauses every request for the time it names`() = realTime {
        val mailbox = TestRelayMailbox()
        mailbox.putRefusal = RelayMailboxResult.RetryLater(RelayMailboxError.STORAGE_FULL, retryAfterSeconds = 60)
        group(mailbox).use { group ->
            val (a, _) = group.devices
            val live = a.startLive()
            awaitStatus(a) { it.storage is DKeyguardRelayStorageStatus.Paused }
            val requests = mailbox.requests.size

            // A local change would be posted, and the peer asked again, if the relay had room.
            a.addForwarder("While full")
            delay(2_000L)
            assertEquals(requests, mailbox.requests.size)
            assertIs<DKeyguardRelayStorageStatus.Paused>(a.registry.get(P2pGroupId.APP_LINK_ID)?.status?.value?.storage)
            assertTrue(mailbox.items(MailboxKind.STATE).isEmpty())
            live.cancel()
        }
    }
}
