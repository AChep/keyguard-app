package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserProtocol
import com.artemchep.keyguard.nativecrypto.NativeCryptoNoise
import com.artemchep.keyguard.provider.keyguard.session.realTime
import com.artemchep.keyguard.provider.keyguard.transport.NoiseByteStream
import com.artemchep.keyguard.provider.keyguard.transport.NoisePinException
import com.artemchep.keyguard.provider.keyguard.transport.NoiseSuite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Kotlin initiator plays the extension against the app's sessions. */
@Suppress("TooManyFunctions")
class BrowserSessionTest {
    @Test
    fun `a frozen extension of every version the app speaks resumes its paired session`() = browserTest { scope ->
        for (version in BrowserProtocol.VERSIONS) {
            val fixture = BrowserFixture(scope)
            val original = paired(fixture)
            fixture.lock()
            val client = fixture.connect(original.keyPair, pinned = original.noise.remoteStatic)
            val file = "/v$version.json"
            val text = requireNotNull(javaClass.getResource(file)) {
                "Missing snapshot $file of a version the app speaks"
            }.readText()
            val hello = Json.parseToJsonElement(text).jsonObject
                .getValue("exchanges").jsonObject
                .getValue("hello").jsonObject
            val response = client.call(hello.getValue("request").toString())
            assertEquals(version, response.int("protocol"), file)
            val expected = hello.getValue("response").jsonObject
            for ((key, value) in expected) {
                if (key == "capabilities") {
                    assertEquals(value.jsonArray.toSet(), response.getValue(key).jsonArray.toSet(), file)
                } else {
                    assertEquals(value, response[key], "$file $key")
                }
            }
        }
    }

    @Test
    fun `an unpaired session may only say hello and pair`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()

        val hello = client.hello()
        assertEquals(setOf("id", "ok", "protocol", "capabilities", "paired", "vault"), hello.keys)
        assertEquals(true, hello.bool("ok"))
        assertEquals(1, hello.int("protocol"))
        assertEquals(false, hello.bool("paired"))
        assertEquals("unlocked", hello.string("vault"))
        // Of the optional features, this version of the app serves passkeys and saving.
        assertEquals("[\"passkeys\",\"save\"]", hello["capabilities"].toString())

        for ((type, error) in listOf(
            "unlock" to "unpaired",
            """suggest","url":"https://example.com""" to "unpaired",
            """fill","handle":"h","url":"https://example.com""" to "unpaired",
            "generate" to "unpaired",
            """save","url":"https://example.com""" to "unpaired",
            "passkey_get" to "unpaired",
            "delete_everything" to "not_supported",
            // Retired: an unknown request.
            "status" to "not_supported",
            """pair_reveal","nonce":"${Base64.encode(ByteArray(32))}""" to "invalid_request",
        )) {
            assertEquals(error, client.call("""{"id":2,"type":"$type"}""").string("error"), type)
        }
        assertEquals("invalid_request", client.call("""{"id":3}""").string("error"))
    }

    @Test
    fun `requests before hello are invalid`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        assertEquals("invalid_request", client.call("""{"id":1,"type":"unlock"}""").string("error"))
        // No version in common.
        val old = client.call("""{"id":2,"type":"hello","protocol":0,"capabilities":[]}""")
        assertEquals("not_supported", old.string("error"))
        assertEquals("invalid_request", client.call("""{"id":3,"type":"unlock"}""").string("error"))
        assertEquals(true, client.hello(id = 4).bool("ok"))
        assertEquals("unpaired", client.call("""{"id":5,"type":"unlock"}""").string("error"))
    }

    @Test
    fun `a future extension gets the highest version of the app`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        val future = BrowserProtocol.VERSIONS.last + 1
        val hello = client.call("""{"id":1,"type":"hello","protocol":$future,"capabilities":["passkeys","save"]}""")
        assertEquals(true, hello.bool("ok"))
        assertEquals(BrowserProtocol.VERSIONS.last, hello.int("protocol"))
        // The session serves requests.
        assertEquals("unpaired", client.call("""{"id":2,"type":"unlock"}""").string("error"))
    }

    @Test
    fun `a session says hello once`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        // A failed hello may be retried.
        assertEquals("invalid_request", client.call("""{"id":1,"type":"hello","protocol":1}""").string("error"))
        val hello = client.call("""{"id":2,"type":"hello","protocol":1,"capabilities":["save"]}""")
        assertEquals("[\"save\"]", hello["capabilities"].toString())

        // Another one is refused, and changes nothing.
        val again = client.call("""{"id":3,"type":"hello","protocol":1,"capabilities":["passkeys","save"]}""")
        assertEquals("invalid_request", again.string("error"))
        assertEquals("not_supported", client.call("""{"id":4,"type":"passkey_get"}""").string("error"))
        assertEquals("unpaired", client.call("""{"id":5,"type":"save","url":"https://example.com"}""").string("error"))
    }

    @Test
    fun `an approved pairing pairs the session under the name the user gave`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        client.hello()

        val (prompt, response) = pair(fixture, client) { request ->
            fixture.service.namePairing(request.id, "  Work browser ")
            fixture.service.respondToApproval(request.id, approved = true)
        }
        // The vault's state; the pairing's ID stays in the app.
        assertEquals(setOf("id", "ok", "vault"), response.keys)
        assertEquals("unlocked", response.string("vault"))
        // The verified browser prefills the name.
        assertEquals("Google Chrome", (prompt.operation as BrowserOperation.Pair).suggestedName)
        assertNull(prompt.pairingName)
        val stored = fixture.store.pairings().single()
        assertEquals("Work browser", stored.name)
        assertEquals(CHROME_ORIGIN, stored.origin)
        assertEquals(stored.id, fixture.service.state.value.sessions.single().pairingId)

        // Paired from now on.
        assertEquals("unlocked", client.call("""{"id":5,"type":"unlock"}""").string("vault"))
        assertEquals("invalid_request", client.call(commit(6, ByteArray(32))).string("error"))
    }

    @Test
    fun `without the browser, the pairing prompt suggests the name the extension gives`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        // The agent couldn't find the process that started the connector.
        val client = fixture.connect(request = fixture.openRequest(caller = null))
        client.hello()

        val (prompt, _) = pair(fixture, client) { request ->
            fixture.service.respondToApproval(request.id, approved = true)
        }
        assertEquals(BrowserCaller(), prompt.session.caller)
        assertEquals("Chrome", (prompt.operation as BrowserOperation.Pair).suggestedName)
        assertEquals("Chrome", fixture.store.pairings().single().name)
    }

    @Test
    fun `a denied pairing leaves the session unpaired`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        client.hello()

        val (_, response) = pair(fixture, client) { request ->
            fixture.service.respondToApproval(request.id, approved = false)
        }
        assertEquals("user_denied", response.string("error"))
        assertTrue(fixture.store.pairings().isEmpty())
        assertEquals("unpaired", client.call("""{"id":5,"type":"unlock"}""").string("error"))
    }

    @Test
    fun `a nonce that does not match its commitment is a security error`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        client.hello()

        val nonce = ByteArray(32) { 7 }
        assertEquals(true, client.call(commit(2, nonce)).bool("ok"))
        val reveal = client.call(reveal(3, ByteArray(32) { 8 }))
        assertEquals("security", reveal.string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())
        // The attempt is gone.
        assertEquals("invalid_request", client.call(reveal(4, nonce)).string("error"))
    }

    @Test
    fun `a locked app pairs nothing`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.lock()
        val client = fixture.connect()
        assertEquals("locked", client.hello().string("vault"))
        assertEquals("vault_locked", client.call(commit(2, ByteArray(32))).string("error"))
    }

    @Test
    fun `a paired extension is paired after a restart, at its own origin only`() = browserTest { scope ->
        val first = BrowserFixture(scope)
        val keyPair = NativeCryptoNoise.generateKeyPair()
        val client = first.connect(keyPair)
        client.hello()
        pair(first, client) { first.service.respondToApproval(it.id, true) }
        val pairingId = first.store.pairings().single().id
        val appKey = client.noise.remoteStatic

        // The app restarts with the same files; the extension pins the app's key.
        val second = BrowserFixture(scope, storage = first.storage, vaultStorage = first.vaultStorage)
        val again = second.connect(keyPair, pinned = appKey)
        assertEquals(true, again.hello().bool("paired"))
        assertEquals(pairingId, second.service.state.value.sessions.single().pairingId)
        assertEquals("unlocked", again.call("""{"id":2,"type":"unlock"}""").string("vault"))

        // The same key at another origin is not paired there.
        val elsewhere = second.connect(keyPair, origin = FIREFOX_ORIGIN, pinned = appKey)
        assertEquals(false, elsewhere.hello().bool("paired"))
        assertEquals("unpaired", elsewhere.call("""{"id":2,"type":"unlock"}""").string("error"))
    }

    @Test
    fun `an extension that pins another key stops before it sends its own`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, handshakeTimeoutMs = 500)
        val pipe = fixture.open()
        assertFailsWith<NoisePinException> {
            NoiseByteStream.handshake(
                stream = pipe.client,
                suite = NoiseSuite.XX,
                initiator = true,
                localPrivateKey = NativeCryptoNoise.generateKeyPair().privateKey,
                pinnedRemoteStatic = NativeCryptoNoise.generateKeyPair().publicKey,
                prologue = BrowserProtocol.prologue(CHROME_ORIGIN),
                timeoutMs = TIMEOUT,
            )
        }
        // The app gives up on the handshake and frees the session.
        withTimeout(TIMEOUT) { pipe.serving.join() }
        assertTrue(fixture.service.state.value.sessions.isEmpty())
    }

    @Test
    fun `a session that names another suite ends without a reply`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val pipe = fixture.open()
        pipe.client.write(byteArrayOf(NoiseSuite.XX_PSK3.id))
        withTimeout(TIMEOUT) { pipe.serving.join() }
        assertNull(pipe.client.read())
    }

    @Test
    fun `a silent extension times the handshake out`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, handshakeTimeoutMs = 300)
        val pipe = fixture.open()
        withTimeout(TIMEOUT) { pipe.serving.join() }
        assertTrue(fixture.service.state.value.sessions.isEmpty())
    }

    @Test
    fun `more than 8 requests in flight are busy, and a repeated id ends the session`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        // Holds back every answer. The one being written is out of flight;
        // the ones that wait for it stay in flight.
        val gate = CompletableDeferred<Unit>()
        client.pipe.writeGate = gate
        client.send(suggest(10))
        // Long enough for the app to start writing its answer.
        delay(500)
        for (id in 11..19) client.send(suggest(id))
        launch {
            // Long enough for the app to read all of them.
            delay(500)
            gate.complete(Unit)
        }
        val answers = List(10) { checkNotNull(client.receive()) }
            .associate { it.long("id") to it }
        assertEquals((10L..19L).toSet(), answers.keys)
        assertEquals("busy", answers.getValue(19).string("error"))
        for (id in 10L..18L) assertEquals(true, answers.getValue(id).bool("ok"))

        // An answered id may be used again; one in flight may not.
        val blocked = CompletableDeferred<Unit>()
        client.pipe.writeGate = blocked
        client.send(suggest(10))
        delay(500)
        client.send(suggest(20))
        client.send(suggest(20))
        launch {
            delay(500)
            blocked.complete(Unit)
        }
        client.awaitEnd()
    }

    @Test
    fun `an answered request is out of flight before the extension reads its answer`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        // Holds back every answer, so that 8 requests stay in flight.
        val gate = CompletableDeferred<Unit>()
        client.pipe.writeGate = gate
        for (id in 10..17) client.send(suggest(id))
        // Long enough for the app to read all of them.
        delay(500)

        // One answer reaches the extension; the app's write of it hasn't returned yet.
        val sent = CompletableDeferred<Unit>()
        client.pipe.sentGate = sent
        gate.complete(Unit)
        val first = checkNotNull(client.receive())
        // The extension frees the slot and sends its next request at once.
        client.send(suggest(20))
        launch {
            // Long enough for the app to read it.
            delay(500)
            client.pipe.sentGate = null
            sent.complete(Unit)
        }
        val answers = (listOf(first) + List(8) { checkNotNull(client.receive()) })
            .associate { it.long("id") to it }
        assertEquals((10L..17L).toSet() + 20L, answers.keys)
        for ((id, answer) in answers) assertEquals(true, answer.bool("ok"), "$id")
    }

    @Test
    fun `fragments are joined, even inside a UTF-8 sequence`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        val text = """{"id":1,"type":"hello","protocol":2,"capabilities":["🔑 1.0","Ключ"]}"""
        val bytes = text.encodeToByteArray()
        // Two bytes into the four of the emoji.
        val cut = text.substringBefore("🔑").encodeToByteArray().size + 2
        client.noise.write(byteArrayOf(1) + bytes.copyOfRange(0, cut))
        client.noise.write(byteArrayOf(0) + bytes.copyOfRange(cut, bytes.size))
        val hello = checkNotNull(client.receive())
        assertEquals(true, hello.bool("ok"))
        // The lower of both sides' highest versions.
        assertEquals(1, hello.int("protocol"))

        // Many small fragments of one message.
        client.send("""{"id":2,"type":"pair_reveal","nonce":"${Base64.encode(ByteArray(32))}"}""", maxData = 4)
        assertEquals("invalid_request", client.receive()?.string("error"))
    }

    @Test
    fun `a reserved bit ends the session`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        client.hello()
        client.noise.write(byteArrayOf(0x02, '{'.code.toByte()))
        client.awaitEnd()
    }

    @Test
    fun `a message that is not a JSON object ends the session`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        client.send("[1,2,3]")
        client.awaitEnd()
    }

    @Test
    fun `a paired session learns when the vault locks and unlocks`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        fixture.lock()
        assertEquals(vaultEvent("locked"), client.receive())
        fixture.unlock()
        assertEquals(vaultEvent("unlocked"), client.receive())
    }

    @Test
    fun `unlock answers once the vault unlocks`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        fixture.lock()
        assertEquals(vaultEvent("locked"), client.receive())

        client.send("""{"id":10,"type":"unlock"}""")
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertIs<BrowserOperation.Unlock>(prompt.operation)
        assertEquals("busy", client.call("""{"id":11,"type":"unlock"}""").string("error"))

        fixture.unlock()
        // The extension learns the state from the answer, the event, or both.
        assertEquals("unlocked", client.receiveResponse(10).string("vault"))
        // The prompt resolved itself.
        fixture.service.approvals.first { it.isEmpty() }
        // At once while unlocked.
        client.send("""{"id":12,"type":"unlock"}""")
        assertEquals("unlocked", client.receiveResponse(12).string("vault"))
    }

    @Test
    fun `a declined unlock is denied`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        fixture.lock()
        client.receive()
        client.send("""{"id":10,"type":"unlock"}""")
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receive()?.string("error"))
    }

    @Test
    fun `removing the pairing revokes the session`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        val pairing = fixture.store.pairings().single()
        fixture.service.removePairing(pairing.id)
        assertEquals("revoked", client.receive()?.string("event"))
        client.awaitEnd()
    }

    @Test
    fun `nothing follows the revoked event`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        // Holds back every write: an answer, then the event, then an answer queued behind it.
        val gate = CompletableDeferred<Unit>()
        client.pipe.writeGate = gate
        client.send("""{"id":10,"type":"unlock"}""")
        // Long enough for the app to start writing its answer.
        delay(500)
        fixture.service.removePairing(fixture.store.pairings().single().id)
        // Long enough for the session to queue the event.
        delay(500)
        client.send("""{"id":11,"type":"unlock"}""")
        delay(500)
        gate.complete(Unit)
        assertEquals("unlocked", client.receive()?.string("vault"))
        assertEquals("revoked", client.receive()?.string("event"))
        assertNull(client.receive())
    }

    @Test
    fun `unlock answers unpaired once the vault unlocks without confirming the pairing`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val keyPair = NativeCryptoNoise.generateKeyPair()
        // Another process writes the extension's key into the pairings outside of the vault.
        val forged = fixture.store.add(
            id = "forged",
            name = "Forged",
            publicKey = keyPair.publicKey,
            origin = CHROME_ORIGIN,
        )
        checkNotNull(forged)
        fixture.lock()
        val client = fixture.connect(keyPair)
        assertEquals(true, client.hello().bool("paired"))

        client.send("""{"id":10,"type":"unlock"}""")
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertIs<BrowserOperation.Unlock>(prompt.operation)
        fixture.unlock()
        // The unlocked vault pairs nothing, and the session learns nothing of it.
        assertEquals("unpaired", client.receive()?.string("error"))
        assertEquals("unpaired", client.call("""{"id":11,"type":"unlock"}""").string("error"))
    }

    @Test
    fun `renaming the pairing keeps the session paired`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        val pairing = fixture.store.pairings().single()
        fixture.service.renamePairing(pairing.id, "Laptop")
        assertEquals(pairing.copy(name = "Laptop"), fixture.store.pairings().single())
        // The settings show the new name.
        fixture.service.state.first { state -> state.pairings.singleOrNull()?.name == "Laptop" }
        // The session still learns the vault's state.
        fixture.lock()
        assertEquals(vaultEvent("locked"), client.receive())
    }

    @Test
    fun `a renamed pairing shows its new name in the prompts of its session`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        val pairing = fixture.store.pairings().single()
        assertEquals("Google Chrome", pairing.name)
        fixture.service.renamePairing(pairing.id, "Laptop")
        fixture.service.state.first { state -> state.pairings.singleOrNull()?.name == "Laptop" }
        fixture.lock()
        assertEquals(vaultEvent("locked"), client.receive())
        client.send("""{"id":10,"type":"unlock"}""")
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertIs<BrowserOperation.Unlock>(prompt.operation)
        assertEquals("Laptop", prompt.pairingName)
    }

    @Test
    fun `turning the integration off ends every session`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val paired = paired(fixture)
        val unpaired = fixture.connect()
        unpaired.hello()
        fixture.service.setEnabled(false)
        assertEquals("disabled", paired.receive()?.string("event"))
        paired.awaitEnd()
        // Events reach paired sessions only.
        assertNull(unpaired.receive())
    }

    @Test
    fun `a cancel while an approved pairing is stored keeps the pairing`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        client.hello()
        assertEquals(true, client.call(commit(2, NONCE)).bool("ok"))
        client.send(reveal(3, NONCE))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()

        // The store stops at its first write, the vault's record.
        val held = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        fixture.vaultStorage.beforeWrite = {
            held.complete(Unit)
            resumed.await()
        }
        fixture.service.respondToApproval(prompt.id, approved = true)
        withTimeout(TIMEOUT) { held.await() }
        assertEquals(true, client.call(cancel(4, target = 3)).bool("ok"))
        resumed.complete(Unit)

        // The pairing stands, and the answer says so.
        assertEquals("unlocked", client.receiveResponse(3).string("vault"))
        val pairing = fixture.store.pairings().single()
        assertEquals(listOf(pairing.id), checkNotNull(fixture.vaultPairings.records()).map { it.id })
        assertEquals("unlocked", client.call("""{"id":5,"type":"unlock"}""").string("vault"))
    }

    @Test
    fun `a cancel after an approved pairing ended keeps its answer`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        client.hello()
        assertEquals(true, client.call(commit(2, NONCE)).bool("ok"))
        client.send(reveal(3, NONCE))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()

        // Holds back every answer: a busy commit is being written, the others wait for it.
        val gate = CompletableDeferred<Unit>()
        client.pipe.writeGate = gate
        client.send(commit(4, NONCE))
        // Long enough for the app to start writing its answer.
        delay(500)
        fixture.service.respondToApproval(prompt.id, approved = true)
        // The pairing is stored and the handling ends; its answer waits.
        withTimeout(TIMEOUT) { while (fixture.store.pairings().isEmpty()) delay(10) }
        delay(500)
        client.send(cancel(5, target = 3))
        delay(500)
        gate.complete(Unit)
        val answers = List(3) { checkNotNull(client.receive()) }.associateBy { it.long("id") }
        assertEquals("busy", answers.getValue(4).string("error"))
        assertEquals("unlocked", answers.getValue(3).string("vault"))
        assertEquals(true, answers.getValue(5).bool("ok"))
    }

    @Test
    fun `an unexpected failure of a logged request answers not_allowed, and the session goes on`() =
        browserTest { scope ->
            val fixture = BrowserFixture(scope)
            val client = fixture.connect()
            client.hello()
            fixture.beforePrompt = { throw IllegalStateException("secret") }
            assertEquals(true, client.call(commit(2, NONCE)).bool("ok"))
            assertEquals("not_allowed", client.call(reveal(3, NONCE)).string("error"))

            // The request log records a failure, and the extension that made the request.
            val row = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_PAIR)
            assertEquals(CliAgentUsageHistoryResponseType.FAILURE, row.response)
            assertEquals("not_allowed", row.errorCode)
            assertEquals(CHROME_ORIGIN, row.browserDetails.extensionOrigin)
            // The app's log names the failure, without its message.
            val logged = fixture.log.messages.single { it.startsWith("Failed to answer a request") }
            assertTrue("IllegalStateException" in logged, logged)
            assertFalse("secret" in logged, logged)

            fixture.beforePrompt = {}
            val (_, response) = pair(fixture, client) { fixture.service.respondToApproval(it.id, true) }
            assertEquals("unlocked", response.string("vault"))
        }

    @Test
    fun `an unexpected failure of an unlock answers not_allowed, and the session goes on`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        fixture.lock()
        assertEquals(vaultEvent("locked"), client.receive())

        fixture.beforePrompt = { throw IllegalStateException("secret") }
        assertEquals("not_allowed", client.call("""{"id":10,"type":"unlock"}""").string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())

        // The next unlock shows a prompt of its own.
        fixture.beforePrompt = {}
        client.send("""{"id":11,"type":"unlock"}""")
        fixture.service.approvals.first { it.isNotEmpty() }
        fixture.unlock()
        assertEquals("unlocked", client.receiveResponse(11).string("vault"))
    }

    @Test
    fun `the requests of a session that wait for the vault share one unlock prompt`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        fixture.lock()
        assertEquals(vaultEvent("locked"), client.receive())

        client.send(passkeyGet(10))
        client.send(passkeyGet(11))
        client.send("""{"id":12,"type":"unlock"}""")
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertIs<BrowserOperation.Unlock>(prompt.operation)
        // Long enough for the app to read all of them.
        delay(500)
        assertEquals(listOf(prompt.id), fixture.service.approvals.value.map { it.id })
        // A second unlock is still busy.
        assertEquals("busy", client.call("""{"id":13,"type":"unlock"}""").string("error"))

        // One answer serves them all.
        fixture.service.respondToApproval(prompt.id, approved = false)
        val answers = List(3) { checkNotNull(client.receive()) }.associate { it.long("id") to it.string("error") }
        assertEquals(mapOf(10L to "user_denied", 11L to "user_denied", 12L to "user_denied"), answers)
        assertTrue(fixture.service.approvals.value.isEmpty())
    }

    private fun vaultEvent(state: String) =
        JsonObject(mapOf("event" to JsonPrimitive("vault"), "state" to JsonPrimitive(state)))

    /** A request without a prompt, which many serve side by side. */
    private fun suggest(id: Int) = """{"id":$id,"type":"suggest","url":"https://example.com"}"""

    private fun cancel(id: Int, target: Int) = """{"id":$id,"type":"cancel","target":$target}"""

    private fun passkeyGet(id: Int) =
        """{"id":$id,"type":"passkey_get","origin":"https://example.com","same_origin_with_ancestors":true,""" +
            """"options":{"challenge":"Y2hhbGxlbmdl","rpId":"example.com"},"mediation":"optional"}"""

    /** The request log's row of the only request of [type]. */
    private suspend fun BrowserFixture.awaitRecorded(type: CliAgentUsageHistoryRequestType) = withTimeout(TIMEOUT) {
        while (recorded().none { it.request == type }) delay(10)
        recorded().single { it.request == type }
    }

    private companion object {
        val NONCE = ByteArray(32) { 1 }
    }
}
