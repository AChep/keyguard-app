package com.artemchep.keyguard.common.service.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** The pairing attempts the user doesn't see, which are scarce app-wide, README section 8. */
class BrowserPairingLimitTest {
    @Test
    fun `three commits without a prompt make the next one busy`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, timeSource = TestTimeSource())
        val client = fixture.connect()
        client.hello()

        // Each replaces the attempt before it, which still counts.
        for (id in 2..4) assertNull(client.commitError(id), "$id")
        assertEquals("busy", client.commitError(5))
        assertEquals("busy", client.commitError(6))

        // The refused commits leave the last attempt pending.
        client.send(reveal(7, NONCE))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("unlocked", checkNotNull(client.receive()).string("vault"))
    }

    @Test
    fun `a commit without a prompt counts for an hour`() = browserTest { scope ->
        val time = TestTimeSource()
        val fixture = BrowserFixture(scope, timeSource = time)
        val client = fixture.connect()
        client.hello()

        assertNull(client.commitError(2))
        time += 20.minutes
        assertNull(client.commitError(3))
        time += 20.minutes
        assertNull(client.commitError(4))
        time += 19.minutes
        assertEquals("busy", client.commitError(5))

        // An hour after the first commit, its slot is free; the others still count.
        time += 1.minutes
        assertNull(client.commitError(6))
        assertEquals("busy", client.commitError(7))
        time += 20.minutes
        assertNull(client.commitError(8))
    }

    @Test
    fun `a commit whose prompt the user answers does not count, so the user may retry`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, timeSource = TestTimeSource())
        val client = fixture.connect()
        client.hello()

        repeat(5) {
            val (_, response) = pair(fixture, client) { fixture.service.respondToApproval(it.id, approved = false) }
            assertEquals("user_denied", response.string("error"))
        }
        // None of the denied attempts counts.
        for (id in 10..12) assertNull(client.commitError(id), "$id")
        assertEquals("busy", client.commitError(13))
    }

    @Test
    fun `a reveal that shows no prompt still counts`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, timeSource = TestTimeSource())
        val client = fixture.connect()
        client.hello()

        for (id in listOf(2, 4)) {
            assertNull(client.commitError(id))
            assertEquals("security", client.call(reveal(id + 1, ByteArray(32) { 8 })).string("error"))
        }
        assertNull(client.commitError(6))
        fixture.lock()
        assertEquals("vault_locked", client.call(reveal(7, NONCE)).string("error"))
        fixture.unlock()

        assertTrue(fixture.service.approvals.value.isEmpty())
        assertEquals("busy", client.commitError(8))
    }

    @Test
    fun `a commit while a reveal is unanswered is busy`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, timeSource = TestTimeSource())
        val client = fixture.connect()
        client.hello()
        assertNull(client.commitError(2))

        // The reveal stops right before its prompt shows.
        val held = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        fixture.beforePrompt = {
            held.complete(Unit)
            resumed.await()
        }
        client.send(reveal(3, NONCE))
        withTimeout(TIMEOUT) { held.await() }
        assertEquals("busy", client.commitError(4))
        fixture.beforePrompt = {}
        resumed.complete(Unit)

        // The only prompt.
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertEquals("busy", client.commitError(5))
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receiveResponse(3).string("error"))
        assertNull(client.commitError(6))
    }

    @Test
    fun `a reveal frees the session whatever its outcome`() = browserTest { scope ->
        val time = TestTimeSource()
        val fixture = BrowserFixture(scope, timeSource = time)
        val client = fixture.connect()
        client.hello()

        assertNull(client.commitError(2))
        assertEquals("security", client.call(reveal(3, ByteArray(32) { 8 })).string("error"))
        assertNull(client.commitError(4))
        fixture.lock()
        assertEquals("vault_locked", client.call(reveal(5, NONCE)).string("error"))
        fixture.unlock()
        assertNull(client.commitError(6))
        client.send(reveal(7, NONCE))
        fixture.service.approvals.first { it.isNotEmpty() }
        client.send("""{"id":8,"type":"cancel","target":7}""")
        val answers = List(2) { checkNotNull(client.receive()) }.associateBy { it.long("id") }
        assertEquals("cancelled", answers.getValue(7).string("error"))

        // Each of the attempts above counts for an hour.
        time += 1.hours
        assertNull(client.commitError(9))
    }

    @Test
    fun `the limit holds across sessions`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, timeSource = TestTimeSource())
        // Each session commits, then ends before it reveals.
        repeat(2) {
            val ended = fixture.connect()
            ended.hello()
            assertNull(ended.commitError(2))
            ended.pipe.client.close()
            withTimeout(TIMEOUT) { ended.pipe.serving.join() }
        }
        val client = fixture.connect()
        client.hello()
        assertNull(client.commitError(2))

        val other = fixture.connect()
        other.hello()
        assertEquals("busy", other.commitError(2))
        assertEquals("busy", client.commitError(3))
    }
}

/** The extension's nonce of every attempt. */
private val NONCE = ByteArray(32) { 7 }

/** Sends `pair_commit`; returns its error, or `null` once the app answers with its nonce. */
private suspend fun BrowserClient.commitError(id: Int): String? = call(commit(id, NONCE)).string("error")
