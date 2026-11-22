package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserError
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserProtocol
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserRequestException
import com.artemchep.keyguard.nativecrypto.NativeCryptoNoise
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The vault's record of each pairing confirms the one outside of it, which any process may write. */
@Suppress("TooManyFunctions")
class BrowserPairingConfirmationTest {
    @Test
    fun `a forged pairing serves while the vault is locked, as the outside record alone does`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val keyPair = NativeCryptoNoise.generateKeyPair()
        fixture.forge(keyPair.publicKey)
        fixture.lock()
        // The settings list the outside records while the vault is locked.
        fixture.service.state.first { state -> state.pairings.map { it.id } == listOf(FORGED_ID) }

        val client = fixture.connect(keyPair)
        val hello = client.hello()
        assertEquals(true, hello.bool("paired"))
        assertEquals("locked", hello.string("vault"))
        assertEquals(FORGED_ID, fixture.service.state.value.sessions.single().pairingId)
        assertNotNull(client.call("""{"id":3,"type":"generate"}""").string("password"))

        // Unlocked, the vault confirms nothing.
        fixture.unlock()
        fixture.service.state.first { state -> state.pairings.isEmpty() }
        assertEquals("unpaired", client.callSkippingEvents(suggest(4)).string("error"))
        assertEquals("unpaired", client.callSkippingEvents("""{"id":5,"type":"generate"}""").string("error"))
    }

    @Test
    fun `a forged pairing is unpaired while the vault is unlocked, and pairs again`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val keyPair = NativeCryptoNoise.generateKeyPair()
        fixture.forge(keyPair.publicKey)

        val client = fixture.connect(keyPair)
        assertEquals(false, client.hello().bool("paired"))
        for ((id, request) in listOf(
            10 to suggest(10),
            11 to """{"id":11,"type":"generate"}""",
            12 to """{"id":12,"type":"save","url":"$PAGE","username":"alice","password":"p"}""",
            13 to """{"id":13,"type":"unlock"}""",
        )) {
            assertEquals("unpaired", client.call(request).string("error"), "$id")
        }
        assertTrue(fixture.generated.isEmpty())

        // The extension pairs again, without the user removing anything.
        val (_, response) = pair(fixture, client) { fixture.service.respondToApproval(it.id, true) }
        assertEquals("unlocked", response.string("vault"))
        val pairingId = fixture.store.pairings().single().id
        assertNotEquals(FORGED_ID, pairingId)
        // The session goes on, paired.
        assertEquals("Login a", client.call(suggest(20)).items().single().string("name"))
        assertEquals(pairingId, fixture.service.state.value.sessions.single().pairingId)
        fixture.service.state.first { state -> state.pairings.map { it.id } == listOf(pairingId) }
    }

    @Test
    fun `hello neither pairs the session with nor touches a pairing the unlocked vault doesn't confirm`() =
        browserTest { scope ->
            val fixture = BrowserFixture(scope)
            val keyPair = NativeCryptoNoise.generateKeyPair()
            // Last used two hours ago: a use would update it.
            val past = object : Clock {
                override fun now() = Clock.System.now() - 2.hours
            }
            assertNotNull(
                BrowserPairingStore(fixture.pairingSettings, Json, fixture.log, past)
                    .add(id = FORGED_ID, name = "Forged", publicKey = keyPair.publicKey, origin = CHROME_ORIGIN),
            )
            val forged = fixture.store.pairings().single()

            val client = fixture.connect(keyPair)
            assertEquals(false, client.hello().bool("paired"))
            assertNull(fixture.service.state.value.sessions.single().pairingId)
            assertEquals(listOf(forged), fixture.store.pairings())

            // Locked, the session stays unpaired until the next hello.
            fixture.lock()
            assertEquals("unpaired", client.callSkippingEvents("""{"id":5,"type":"generate"}""").string("error"))
            assertTrue(fixture.generated.isEmpty())
        }

    @Test
    fun `a failed note of the use of a pairing keeps the session paired`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val original = paired(fixture)
        // Last used long ago: the next hello notes the use.
        val setting = fixture.pairingSettings.getString(KEY_PAIRINGS, "")
        val stale = setting.first().replace(Regex(""""lastUsedAt":\d+"""), """"lastUsedAt":0""")
        setting.setAndCommit(stale).bind()
        val stored = fixture.store.pairings().single()
        assertEquals(0L, stored.lastUsedAt.toEpochMilliseconds())
        // The disk is full.
        fixture.storage.failWrites = true

        val client = fixture.connect(original.keyPair, pinned = original.noise.remoteStatic)
        assertEquals(true, client.hello().bool("paired"))
        assertEquals(stored.id, fixture.service.state.value.sessions.last().pairingId)
        assertEquals("unlocked", client.call("""{"id":2,"type":"unlock"}""").string("vault"))
        assertEquals(listOf(stored), fixture.store.pairings())
        assertTrue(fixture.log.messages.any { it.startsWith("Failed to note the use of a pairing") })
    }

    @Test
    fun `a forged pairing gets unpaired once a passkey request unlocks the vault`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        val keyPair = NativeCryptoNoise.generateKeyPair()
        fixture.forge(keyPair.publicKey)
        fixture.lock()
        val client = fixture.connect(keyPair)
        assertEquals(true, client.hello().bool("paired"))

        client.send(
            """{"id":5,"type":"passkey_get","origin":"https://example.com",""" +
                """"options":{"challenge":"AAAA","rpId":"example.com"},""" +
                """"mediation":"optional","same_origin_with_ancestors":true}""",
        )
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertEquals(BrowserOperation.Unlock, prompt.operation)
        fixture.unlock()
        assertEquals("unpaired", client.receiveResponse(5).string("error"))
        // Nothing else was asked.
        withTimeout(TIMEOUT) { fixture.service.approvals.first { it.isEmpty() } }
        assertTrue(fixture.passkeys.passkeyPickers.value.isEmpty())
    }

    @Test
    fun `a vault record of another key or origin confirms nothing`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        assertEquals(true, client.call(suggest(5)).bool("ok"))
        val record = checkNotNull(fixture.vaultPairings.records()).single()

        fixture.vaultPairings.update { listOf(record.copy(origin = FIREFOX_ORIGIN)) }
        assertEquals("unpaired", client.call(suggest(6)).string("error"))
        assertEquals("unpaired", client.call("""{"id":7,"type":"generate"}""").string("error"))

        val otherKey = Base64.encode(NativeCryptoNoise.generateKeyPair().publicKey)
        fixture.vaultPairings.update { listOf(record.copy(publicKey = otherKey)) }
        assertEquals("unpaired", client.call(suggest(8)).string("error"))

        fixture.vaultPairings.update { listOf(record.copy(id = "another")) }
        assertEquals("unpaired", client.call(suggest(9)).string("error"))

        fixture.vaultPairings.update { listOf(record) }
        assertEquals(true, client.call(suggest(10)).bool("ok"))
    }

    @Test
    fun `pairing writes both records`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        val pairing = fixture.store.pairings().single()
        val record = checkNotNull(fixture.vaultPairings.records()).single()
        assertEquals(
            BrowserPairingIdentity(
                id = pairing.id,
                publicKey = Base64.encode(client.keyPair.publicKey),
                origin = CHROME_ORIGIN,
            ),
            record,
        )
        assertEquals(listOf(pairing), fixture.service.state.first { it.pairings.isNotEmpty() }.pairings)
    }

    @Test
    fun `a failed write of the vault record pairs nothing`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        client.hello()
        fixture.vaultStorage.failWrites = true
        val (_, response) = pair(fixture, client) { fixture.service.respondToApproval(it.id, true) }
        assertEquals("not_allowed", response.string("error"))
        assertTrue(fixture.store.pairings().isEmpty())
        assertEquals("unpaired", client.call("""{"id":5,"type":"unlock"}""").string("error"))

        // Another attempt pairs.
        fixture.vaultStorage.failWrites = false
        val (_, again) = pair(fixture, client) { fixture.service.respondToApproval(it.id, true) }
        assertEquals("unlocked", again.string("vault"))
        val pairingId = fixture.store.pairings().single().id
        assertEquals(listOf(pairingId), checkNotNull(fixture.vaultPairings.records()).map { it.id })
    }

    @Test
    fun `a failed write of the outside record takes the vault record back`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        client.hello()
        fixture.storage.failWrites = true
        val (_, response) = pair(fixture, client) { fixture.service.respondToApproval(it.id, true) }
        assertEquals("not_allowed", response.string("error"))
        assertTrue(fixture.store.pairings().isEmpty())
        assertEquals(emptyList(), fixture.vaultPairings.records())
    }

    @Test
    fun `removing a pairing removes both records, and a write prunes the vault's`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        paired(fixture)
        val pairing = fixture.store.pairings().single()
        fixture.service.removePairing(pairing.id)
        assertTrue(fixture.store.pairings().isEmpty())
        assertEquals(emptyList(), fixture.vaultPairings.records())

        // A record without one outside of the vault goes with the next write.
        val stale = BrowserPairingIdentity(
            id = "stale",
            publicKey = Base64.encode(NativeCryptoNoise.generateKeyPair().publicKey),
            origin = CHROME_ORIGIN,
        )
        fixture.vaultPairings.update { it + stale }
        paired(fixture)
        val other = fixture.store.pairings().single()
        assertEquals(listOf(other.id), checkNotNull(fixture.vaultPairings.records()).map { it.id })
    }

    @Test
    fun `removing a pairing while the vault is locked fails and keeps both records`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        val pairing = fixture.store.pairings().single()
        val record = checkNotNull(fixture.vaultPairings.records()).single()
        fixture.lock()
        val error = assertFailsWith<BrowserRequestException> { fixture.service.removePairing(pairing.id) }
        assertEquals(BrowserError.VAULT_LOCKED, error.code)
        assertEquals(listOf(pairing), fixture.store.pairings())
        assertEquals(listOf(record), fixture.vaultPairings.records())

        // The session stays paired.
        fixture.unlock()
        assertEquals(true, client.callSkippingEvents(suggest(5)).bool("ok"))
    }

    @Test
    fun `an outside record written back after a removal pairs nothing while the vault is unlocked`() =
        browserTest { scope ->
            val fixture = BrowserFixture(scope)
            val original = paired(fixture)
            val setting = fixture.pairingSettings.getString(KEY_PAIRINGS, "")
            val saved = setting.first()
            val pairing = fixture.store.pairings().single()
            fixture.service.removePairing(pairing.id)
            assertEquals("revoked", original.receive()?.string("event"))
            assertEquals(emptyList(), fixture.vaultPairings.records())

            // Another process writes the outside record back.
            setting.setAndCommit(saved).bind()
            assertEquals(listOf(pairing), fixture.store.pairings())
            val client = fixture.connect(original.keyPair, pinned = original.noise.remoteStatic)
            assertEquals(false, client.hello().bool("paired"))
            assertEquals("unpaired", client.call(suggest(2)).string("error"))
            assertEquals(emptyList(), fixture.vaultPairings.records())
        }

    @Test
    fun `a failed removal of the outside record puts the vault record back`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        val pairing = fixture.store.pairings().single()
        val record = checkNotNull(fixture.vaultPairings.records()).single()
        // The disk is full.
        fixture.storage.failWrites = true
        val error = assertFailsWith<BrowserRequestException> { fixture.service.removePairing(pairing.id) }
        assertEquals(BrowserError.NOT_ALLOWED, error.code)
        assertEquals(listOf(pairing), fixture.store.pairings())
        assertEquals(listOf(record), fixture.vaultPairings.records())
        // The session stays paired, and the settings still list the pairing.
        assertEquals(true, client.call(suggest(5)).bool("ok"))
        fixture.service.state.first { state -> state.pairings.map { it.id } == listOf(pairing.id) }

        // Another attempt removes it.
        fixture.storage.failWrites = false
        fixture.service.removePairing(pairing.id)
        assertEquals("revoked", client.receive()?.string("event"))
        assertTrue(fixture.store.pairings().isEmpty())
        assertEquals(emptyList(), fixture.vaultPairings.records())
    }

    @Test
    fun `a request during a removal waits for it, and stays paired once it fails`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        val pairing = fixture.store.pairings().single()
        val record = checkNotNull(fixture.vaultPairings.records()).single()
        // The outside delete stops at its write, which then fails.
        val held = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        fixture.storage.beforeWrite = {
            held.complete(Unit)
            resumed.await()
        }
        fixture.storage.failWrites = true
        val removal = scope.async { fixture.service.removePairing(pairing.id) }
        withTimeout(TIMEOUT) { held.await() }
        assertEquals(emptyList(), fixture.vaultPairings.records())
        client.send(suggest(5))
        // Long enough for the app to check the pairing.
        delay(500)
        resumed.complete(Unit)

        val error = assertFailsWith<BrowserRequestException> { removal.await() }
        assertEquals(BrowserError.NOT_ALLOWED, error.code)
        assertEquals(true, client.receiveResponse(5).bool("ok"))
        assertEquals(listOf(record), fixture.vaultPairings.records())
        assertEquals(pairing.id, fixture.service.state.value.sessions.single().pairingId)
        assertEquals(true, client.call(suggest(6)).bool("ok"))
    }

    @Test
    fun `a removal whose outside delete fails after it took effect stands`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        val pairing = fixture.store.pairings().single()
        // The record is deleted, then the flush fails.
        fixture.storage.failAfterWrites = true
        fixture.service.removePairing(pairing.id)
        fixture.storage.failAfterWrites = false
        assertEquals("revoked", client.receive()?.string("event"))
        assertTrue(fixture.store.pairings().isEmpty())
        // No vault record is left for an outside record written back to match.
        assertEquals(emptyList(), fixture.vaultPairings.records())
    }

    @Test
    fun `a removal whose outside delete fails after it took effect, unreadable since, puts the vault record back`() =
        browserTest { scope ->
            val fixture = BrowserFixture(scope)
            paired(fixture)
            val pairing = fixture.store.pairings().single()
            val record = checkNotNull(fixture.vaultPairings.records()).single()
            // The record is deleted, then the flush fails, and so does every read since.
            fixture.storage.failAfterWrites = true
            fixture.storage.failReads = true
            val error = assertFailsWith<BrowserRequestException> { fixture.service.removePairing(pairing.id) }
            assertEquals(BrowserError.NOT_ALLOWED, error.code)
            assertEquals(listOf(record), fixture.vaultPairings.records())
        }

    @Test
    fun `an outside store that can't reload fails removal and hello, and prunes nothing in the vault`() =
        browserTest { scope ->
            val fixture = BrowserFixture(scope)
            val original = paired(fixture)
            paired(fixture)
            val pairings = fixture.store.pairings()
            val records = checkNotNull(fixture.vaultPairings.records())
            // A rename reaches the disk, then fails, and so does the reload after it.
            fixture.storage.failAfterWrites = true
            fixture.storage.failReads = true
            assertFails { fixture.service.renamePairing(pairings.first().id, "Laptop") }
            fixture.storage.failAfterWrites = false

            val error = assertFailsWith<BrowserRequestException> { fixture.service.removePairing(pairings.last().id) }
            assertEquals(BrowserError.NOT_ALLOWED, error.code)
            assertEquals(records, fixture.vaultPairings.records())
            // A lookup that can't read answers an error, which keeps the extension's pin.
            val client = fixture.connect(original.keyPair, pinned = original.noise.remoteStatic)
            assertEquals("not_allowed", client.hello().string("error"))

            fixture.storage.failReads = false
            assertEquals(true, client.hello(2).bool("paired"))
            assertEquals(records, fixture.vaultPairings.records())
        }

    @Test
    fun `a new name is trimmed and bounded like the one given while pairing`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        paired(fixture)
        val pairing = fixture.store.pairings().single()
        // Two UTF-16 units each: the bound counts code points.
        fixture.service.renamePairing(pairing.id, " " + "🔐".repeat(BrowserProtocol.MAX_NAME_LENGTH + 1))
        val bounded = "🔐".repeat(BrowserProtocol.MAX_NAME_LENGTH)
        assertEquals(bounded, fixture.store.pairings().single().name)
        // A blank name changes nothing.
        fixture.service.renamePairing(pairing.id, "   ")
        assertEquals(bounded, fixture.store.pairings().single().name)
    }

    @Test
    fun `pairings of a newer schema outside of the vault are neither read nor overwritten`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val original = paired(fixture)
        val setting = fixture.pairingSettings.getString(KEY_PAIRINGS, "")
        val saved = setting.first()
        setting.setAndCommit(NEWER).bind()
        assertNull(original.receive(), "An unreadable store must not send revoked")
        assertTrue(fixture.store.pairings().isEmpty())

        val client = fixture.connect(original.keyPair, pinned = original.noise.remoteStatic)
        assertEquals("update_required", client.hello().string("error"))
        assertEquals("invalid_request", client.call(commit(2, ByteArray(32))).string("error"))
        val records = fixture.vaultPairings.records()
        fixture.service.renamePairing("x", "Laptop")
        val error = assertFailsWith<BrowserRequestException> { fixture.service.removePairing("x") }
        assertEquals(BrowserError.UPDATE_REQUIRED, error.code)
        assertEquals(NEWER, setting.first())
        assertEquals(records, fixture.vaultPairings.records())

        setting.setAndCommit(saved).bind()
        assertEquals(true, client.hello(3).bool("paired"))
    }

    @Test
    fun `a pinned extension resumes after the vault pairing format is readable again`() = browserTest { scope ->
        val first = BrowserFixture(scope)
        val original = paired(first)
        val saved = first.vaultStorage.get(KEY_VAULT_PAIRINGS) as String
        val pin = original.noise.remoteStatic
        original.noise.close()
        first.vaultStorage.put(KEY_VAULT_PAIRINGS, NEWER)

        val older = BrowserFixture(scope, storage = first.storage, vaultStorage = first.vaultStorage)
        assertNull(older.vaultPairings.records())
        assertTrue(older.vaultPairings.identities.first().isEmpty())
        val client = older.connect(original.keyPair, pinned = pin)
        assertEquals("update_required", client.hello().string("error"))
        older.lock()
        assertEquals(true, client.hello(2).bool("paired"))
        client.send("""{"id":3,"type":"unlock"}""")
        older.service.approvals.first { it.isNotEmpty() }
        older.unlock()
        assertEquals("update_required", client.receiveResponse(3).string("error"))
        assertEquals("update_required", client.callSkippingEvents(suggest(4)).string("error"))
        client.noise.close()

        assertEquals(NEWER, first.vaultStorage.get(KEY_VAULT_PAIRINGS))
        first.vaultStorage.put(KEY_VAULT_PAIRINGS, saved)
        val restored = BrowserFixture(scope, storage = first.storage, vaultStorage = first.vaultStorage)
        assertEquals(true, restored.connect(original.keyPair, pinned = pin).hello().bool("paired"))
    }

    @Test
    fun `an unreadable app key is kept, logged once, and no session starts`() = browserTest { scope ->
        val storage = MemoryJsonStore()
        storage.put(KEY_APP_KEY, "{\"schemaVersion\":2}")
        val fixture = BrowserFixture(scope, storage = storage)
        assertNull(fixture.store.appKeyPair())
        repeat(3) {
            val pipe = fixture.open()
            withTimeout(TIMEOUT) { pipe.serving.join() }
        }
        assertEquals("{\"schemaVersion\":2}", storage.get(KEY_APP_KEY))
        val logged = fixture.log.entries.filter { it.tag == "BrowserPairingStore" }
        assertEquals(listOf(LogLevel.ERROR), logged.map { it.level })
        assertTrue(logged.none { "schemaVersion" in it.message })
    }
}

private const val PAGE = "https://example.com/"
private const val FORGED_ID = "forged"
private const val KEY_APP_KEY = "app_static_key"
private const val KEY_PAIRINGS = "pairings"
private const val KEY_VAULT_PAIRINGS = "browser_extension.pairings"
private const val NEWER = """{"schemaVersion":2,"pairings":[{"id":"x","keys":["a"]}]}"""

private fun suggest(id: Int) = """{"id":$id,"type":"suggest","url":"$PAGE"}"""

/** Another process writes the extension's key into the pairings outside of the vault. */
private suspend fun BrowserFixture.forge(publicKey: ByteArray) {
    assertNotNull(store.add(id = FORGED_ID, name = "Forged", publicKey = publicKey, origin = CHROME_ORIGIN))
}

private suspend fun MemoryJsonStore.put(key: String, value: String) {
    JsonKeyValueStore(this).getString(key, "").setAndCommit(value).bind()
}

private suspend fun MemoryJsonStore.get(key: String): Any? = read().bind()[key]

private fun JsonObject.items(): List<JsonObject> = (getValue("items") as JsonArray).map { it.jsonObject }

/** Skips the events that a change of the vault's state sends. */
private suspend fun BrowserClient.callSkippingEvents(request: String): JsonObject {
    send(request)
    val id = checkNotNull(Regex(""""id":(\d+)""").find(request)).groupValues[1].toLong()
    return receiveResponse(id)
}
