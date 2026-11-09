package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class AppSyncWriteBudgetTest {
    @Test
    fun `records that name one blob again and again hold no more rows than the device may`() {
        var counts = 0
        val budget = AppSyncWriteBudget(rows = 100, maxHeld = 25)
        val countHeld = {
            counts++
            5L
        }
        // 5 rows were here, so two lists of 10 fit, and the third does not.
        repeat(2) { assertTrue(budget.take(count = 10, replaced = 0, countHeld = countHeld)) }
        assertFalse(budget.take(count = 10, replaced = 0, countHeld = countHeld))
        // Rows that replace as many fit, and nothing waits for a next pass.
        assertTrue(budget.take(count = 10, replaced = 10, countHeld = countHeld))
        assertFalse(budget.deferred)
        assertEquals(1, counts)
    }

    @Test
    fun `a pass counts the rows once, even when every record is refused`() {
        var counts = 0
        val budget = AppSyncWriteBudget(rows = 100, maxHeld = 25)
        val countHeld = {
            counts++
            20L
        }
        repeat(3) {
            assertFalse(budget.take(count = 10, replaced = 0, countHeld = countHeld))
        }
        assertEquals(1, counts)
    }

    @Test
    fun `rows a pass deleted make room for the records after them`() {
        var counts = 0
        var heldRows = 20L
        val budget = AppSyncWriteBudget(rows = 100, maxHeld = 25)
        val countHeld = {
            counts++
            heldRows
        }
        assertFalse(budget.take(count = 10, replaced = 0, countHeld = countHeld))
        heldRows = 5L
        budget.recount()
        assertTrue(budget.take(count = 10, replaced = 0, countHeld = countHeld))
        assertEquals(2, counts)
    }

    @Test
    fun `a wordlist a pass deletes makes room for the wordlists after it`() = runTest {
        val animals = listOf("cat", "dog", "ant")
        AppLinkTestDevice("A").use { a ->
            a.addWordlist("Animals", animals)
            a.addWordlist("Large", List(20) { "word$it" })
            val large = a.wordlistSyncId("Large")
            val bytes = WordlistBlob.encode(animals)
            val hash = BlobExchange.hashOf(a.crypto, bytes)
            val received = AppLinkReceivedBlobs()
            received.put(hash, bytes)
            val category = WordlistsSyncCategory(
                hashOf = { BlobExchange.hashOf(a.crypto, it) },
                received = received,
            )
            val body = buildJsonObject {
                put("name", "Pets")
                put("words", hash)
            }
            val now = Instant.fromEpochMilliseconds(a.time)
            val budget = AppSyncWriteBudget(rows = 1_000, maxHeld = 25)

            // 23 words are here: 3 more do not fit, until the large list is gone.
            assertFalse(category.write(a.db, "x", body, now, budget))
            category.delete(a.db, large, budget)
            assertTrue(category.write(a.db, "y", body, now, budget))

            assertEquals(
                mapOf("Animals" to animals.sorted(), "Pets" to animals.sorted()),
                a.wordlistWords(),
            )
            assertEquals("y", a.wordlistSyncId("Pets"))
            assertFalse(budget.deferred)
        }
    }

    @Test
    fun `a pass that wrote all it may skips the rest for the next pass`() {
        val budget = AppSyncWriteBudget(rows = 10, maxHeld = 1_000)
        assertFalse(budget.skips())
        // The first write of a pass always fits.
        assertTrue(budget.take(count = 15, replaced = 0) { 0L })
        assertTrue(budget.skips())
        assertTrue(budget.deferred)
    }
}
