package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The `cancel` request, README section 5, and the end of a session, which stops every request too. */
class BrowserCancelTest {
    @Test
    fun `cancel takes the prompt off the screen and answers cancelled`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        val handle = client.itemHandle(10)

        client.send(fill(11, handle))
        fixture.service.approvals.first { it.isNotEmpty() }
        client.send(cancel(12, target = 11))
        val responses = client.responses(11, 12)
        assertEquals(mapOf("id" to "12", "ok" to "true"), responses.getValue(12).flat())
        assertEquals("cancelled", responses.getValue(11).string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())
        val log = fixture.awaitLog(CliAgentUsageHistoryRequestType.BROWSER_FILL)
        assertEquals(CliAgentUsageHistoryResponseType.CANCELLED, log.response)

        // The session goes on.
        client.send(fill(13, handle))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("secret-a", client.responses(13).getValue(13).string("password"))
    }

    @Test
    fun `a cancelled passkey_create stores nothing`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)

        client.send(passkeyCreate(10))
        val picker = fixture.passkeys.passkeyPickers.first { it.isNotEmpty() }.single()
        client.send(cancel(11, target = 10))
        assertEquals("cancelled", client.responses(10, 11).getValue(10).string("error"))
        assertTrue(fixture.passkeys.passkeyPickers.value.isEmpty())
        // A pick that comes too late.
        assertIs<AppMode.SavePasskey>(picker.mode).onComplete(fixture.ciphers.value.single())
        delay(100)
        assertTrue(checkNotNull(fixture.ciphers.value.single().login).fido2Credentials.isEmpty())
    }

    @Test
    fun `a cancelled passkey_get takes the pick prompt off the screen`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        val client = paired(fixture)

        client.send(passkeyGet(10))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertIs<BrowserOperation.PasskeyPick>(prompt.operation)
        client.send(cancel(11, target = 10))
        assertEquals("cancelled", client.responses(10, 11).getValue(10).string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())

        // The session may ask for a passkey again.
        client.send(passkeyCreate(12))
        fixture.passkeys.passkeyPickers.first { it.isNotEmpty() }
        client.send(cancel(13, target = 12))
        assertEquals("cancelled", client.responses(12, 13).getValue(12).string("error"))
    }

    @Test
    fun `cancel closes a save window that hasn't shown, and an unlock prompt`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)

        client.send("""{"id":10,"type":"save","url":"https://example.com/","username":"bob","password":"new"}""")
        fixture.service.saves.first { it.isNotEmpty() }
        client.send(cancel(11, target = 10))
        assertEquals("cancelled", client.responses(10, 11).getValue(10).string("error"))
        assertTrue(fixture.service.saves.value.isEmpty())

        fixture.lock()
        client.send("""{"id":12,"type":"unlock"}""")
        assertIs<BrowserOperation.Unlock>(fixture.service.approvals.first { it.isNotEmpty() }.single().operation)
        client.send(cancel(13, target = 12))
        assertEquals("cancelled", client.responses(12, 13).getValue(12).string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())
    }

    @Test
    fun `cancel is answered at once, and never counts as in flight`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        val handle = client.itemHandle(10)

        // Nothing in flight: unknown, and already answered.
        assertEquals(true, client.call(cancel(11, target = 99)).bool("ok"))
        assertEquals(true, client.call(cancel(12, target = 10)).bool("ok"))
        assertEquals("invalid_request", client.call("""{"id":13,"type":"cancel"}""").string("error"))
        assertEquals("invalid_request", client.call("""{"id":14,"type":"cancel","target":"x"}""").string("error"))

        // Eight requests in flight.
        for (id in 20L..27L) client.send(fill(id, handle))
        fixture.service.approvals.first { it.size == 8 }
        assertEquals("busy", client.call(fill(28, handle)).string("error"))
        client.send(cancel(29, target = 20))
        val responses = client.responses(20, 29)
        assertEquals(true, responses.getValue(29).bool("ok"))
        assertEquals("cancelled", responses.getValue(20).string("error"))
        fixture.service.approvals.first { it.size == 7 }
    }

    @Test
    fun `cancel after the handling ended still cancels the answer`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        // Holds back every answer: the first one is being written, the others wait for it.
        val gate = CompletableDeferred<Unit>()
        client.pipe.writeGate = gate
        client.send("""{"id":10,"type":"unlock"}""")
        // Long enough for the app to start writing its answer.
        delay(500)
        // Its handling ends at once; its answer waits.
        client.send("""{"id":11,"type":"unlock"}""")
        delay(500)
        client.send(cancel(12, target = 11))
        delay(500)
        gate.complete(Unit)
        val responses = client.responses(10, 11, 12)
        assertEquals("unlocked", responses.getValue(10).string("vault"))
        assertEquals("cancelled", responses.getValue(11).string("error"))
        assertEquals(true, responses.getValue(12).bool("ok"))
    }

    @Test
    fun `revoking the pairing stops the requests before its event is written`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        assertEndStopsRequests(fixture, event = "revoked") {
            fixture.service.removePairing(fixture.store.pairings().single().id)
        }
    }

    @Test
    fun `turning the integration off stops the requests before its event is written`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        assertEndStopsRequests(fixture, event = "disabled") {
            fixture.service.setEnabled(false)
        }
    }

    /**
     * Ends a session that has a prompt and a passkey window open with [end],
     * while every write waits: both close before the [event] is written, and
     * the [event] is the only message that follows. The stopped requests get
     * no answers.
     */
    private suspend fun assertEndStopsRequests(
        fixture: BrowserFixture,
        event: String,
        end: suspend () -> Unit,
    ) {
        fixture.passkeyLogin("a")
        val client = paired(fixture)
        val handle = client.itemHandle(10)
        client.send(fill(11, handle))
        fixture.service.approvals.first { it.isNotEmpty() }
        client.send(passkeyCreate(12))
        fixture.passkeys.passkeyPickers.first { it.isNotEmpty() }

        val gate = CompletableDeferred<Unit>()
        client.pipe.writeGate = gate
        end()
        withTimeout(TIMEOUT) {
            fixture.service.approvals.first { it.isEmpty() }
            fixture.passkeys.passkeyPickers.first { it.isEmpty() }
        }
        // A request sent meanwhile is dropped.
        client.send(fill(13, handle))
        delay(500)
        assertTrue(fixture.service.approvals.value.isEmpty())

        gate.complete(Unit)
        val messages = mutableListOf<JsonObject>()
        while (true) messages += client.receive() ?: break
        assertEquals(listOf(event), messages.map { it.string("event") }, "$messages")
    }

    @Test
    fun `cancel before hello is invalid`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        assertEquals("invalid_request", client.call(cancel(1, target = 2)).string("error"))
        assertEquals(true, client.hello(id = 3).bool("ok"))
        // Any session may cancel.
        assertEquals(true, client.call(cancel(4, target = 5)).bool("ok"))
    }
}

private fun cancel(id: Long, target: Long) = """{"id":$id,"type":"cancel","target":$target}"""

private fun fill(id: Long, handle: String) =
    """{"id":$id,"type":"fill","handle":"$handle","url":"https://example.com/"}"""

private fun passkeyGet(id: Long) =
    """{"id":$id,"type":"passkey_get","origin":"https://example.com","same_origin_with_ancestors":true,""" +
        """"options":{"challenge":"Y2hhbGxlbmdl","rpId":"example.com"},"mediation":"optional"}"""

private fun passkeyCreate(id: Long) =
    """{"id":$id,"type":"passkey_create","origin":"https://example.com","same_origin_with_ancestors":true,""" +
        """"options":{"challenge":"Y2hhbGxlbmdl","rp":{"id":"example.com","name":"Example"},""" +
        """"user":{"id":"dXNlcg","name":"bob","displayName":"Bob"},""" +
        """"pubKeyCredParams":[{"type":"public-key","alg":-7}]}}"""

/** The handle of the only login of the page. */
private suspend fun BrowserClient.itemHandle(id: Long): String {
    val items = call("""{"id":$id,"type":"suggest","url":"https://example.com/"}""").getValue("items") as JsonArray
    return checkNotNull(items.single().jsonObject.string("handle"))
}

/** The responses to the requests [ids], in any order, skipping the events meanwhile. */
private suspend fun BrowserClient.responses(vararg ids: Long): Map<Long, JsonObject> {
    val responses = mutableMapOf<Long, JsonObject>()
    while (!responses.keys.containsAll(ids.toList())) {
        val message = checkNotNull(receive()) { "The session ended" }
        val id = message["id"]?.jsonPrimitive?.content?.toLong()
            ?: continue
        responses[id] = message
    }
    return responses
}

private fun JsonObject.flat(): Map<String, String> = mapValues { (_, value) -> value.jsonPrimitive.content }

private suspend fun BrowserFixture.awaitLog(type: CliAgentUsageHistoryRequestType) = withTimeout(TIMEOUT) {
    while (recorded().none { it.request == type }) delay(10)
    recorded().single { it.request == type }
}
