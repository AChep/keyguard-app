package com.artemchep.keyguard.common.util

import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkipReason
import com.artemchep.keyguard.common.service.credentialexchange.cxfExportSkips
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.importSkips
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The tally's own contract. Everything the rest of the import and export code
 * assumes about [SkipTally] — that the total is derived, that a non-positive
 * count is never stored, that two tallies built differently but counting the
 * same things are equal, and that the two directions never compare equal — is
 * asserted here rather than left to the reader.
 */
class SkipTallyTest {
    @Test
    fun `merging builders adds counts and titles without sharing mutable state`() {
        val parent = SkipTally.Builder(ImportSkipReason::class)
        parent.add(ImportSkipReason.Item, "Mail")
        val record = SkipTally.Builder(ImportSkipReason::class)
        record.add(ImportSkipReason.Item, "Mail", count = 2)
        record.add(ImportSkipReason.Otp, "Bank")
        record.add(ImportSkipReason.Otp, count = 2)

        parent.add(record)
        record.add(ImportSkipReason.Item, "Mail")
        record.add(ImportSkipReason.Otp, "Bank")
        parent.add(ImportSkipReason.Otp, "Forum")

        val tally = parent.build()
        assertEquals(importSkips(ImportSkipReason.Item to 3, ImportSkipReason.Otp to 4), tally)
        assertEquals(mapOf("Mail" to 3), tally.titlesOf(ImportSkipReason.Item))
        assertEquals(mapOf("Bank" to 1, "Forum" to 1), tally.titlesOf(ImportSkipReason.Otp))
        assertEquals(mapOf("Bank" to 2), record.build().titlesOf(ImportSkipReason.Otp))
    }

    @Test
    fun `builder preserves counts and title attribution`() {
        val builder = SkipTally.Builder(ImportSkipReason::class)
        builder.add(ImportSkipReason.Item, "Mail", count = 2)
        builder.add(ImportSkipReason.Item, "Mail")
        builder.add(ImportSkipReason.Item, "Bank")
        builder.add(ImportSkipReason.Item, "   ")
        builder.add(ImportSkipReason.Item)
        builder.add(ImportSkipReason.Otp, "Mail", count = 3)
        builder.add(ImportSkipReason.Otp, "Ignored", count = 0)
        builder.add(ImportSkipReason.Passkey, "Ignored", count = -1)

        val tally = builder.build()
        assertEquals(importSkips(ImportSkipReason.Item to 6, ImportSkipReason.Otp to 3), tally)
        assertEquals(9, tally.totalCount)
        assertEquals(mapOf("Mail" to 3, "Bank" to 1), tally.titlesOf(ImportSkipReason.Item))
        assertEquals(listOf("Mail", "Bank"), tally.titlesOf(ImportSkipReason.Item).keys.toList())
        assertEquals(mapOf("Mail" to 3), tally.titlesOf(ImportSkipReason.Otp))
        assertTrue(tally.titlesOf(ImportSkipReason.Passkey).isEmpty())
        assertEquals(listOf(ImportSkipReason.Otp to 3, ImportSkipReason.Item to 6), tally.counted)
    }

    @Test
    fun `builder snapshots do not change after further additions`() {
        val builder = SkipTally.Builder(ImportSkipReason::class)
        val empty = builder.build()
        builder.add(ImportSkipReason.Item, "Mail")
        builder.add(ImportSkipReason.Item, "Bank")
        val first = builder.build()

        builder.add(ImportSkipReason.Item, "Mail", count = 2)
        builder.add(ImportSkipReason.Item, "Forum")
        builder.add(ImportSkipReason.Otp, "Mail")
        val second = builder.build()

        assertTrue(empty.isEmpty)
        assertTrue(empty.titlesOf(ImportSkipReason.Item).isEmpty())
        assertEquals(importSkips(ImportSkipReason.Item to 2), first)
        assertEquals(2, first.totalCount)
        assertEquals(mapOf("Mail" to 1, "Bank" to 1), first.titlesOf(ImportSkipReason.Item))
        assertTrue(first.titlesOf(ImportSkipReason.Otp).isEmpty())
        assertEquals(importSkips(ImportSkipReason.Item to 5, ImportSkipReason.Otp to 1), second)
        assertEquals(mapOf("Mail" to 3, "Bank" to 1, "Forum" to 1), second.titlesOf(ImportSkipReason.Item))
        assertEquals(mapOf("Mail" to 1), second.titlesOf(ImportSkipReason.Otp))
    }

    @Test
    fun `the total is the sum of what was counted`() {
        val skips = importSkips(
            ImportSkipReason.Passkey to 2,
            ImportSkipReason.Item to 3,
        )
        assertEquals(5, skips.totalCount)
        assertEquals(2, skips[ImportSkipReason.Passkey])
        assertEquals(3, skips[ImportSkipReason.Item])
        assertEquals(0, skips[ImportSkipReason.Account])
    }

    @Test
    fun `a non-positive count is not stored`() {
        listOf(
            "zero" to importSkips(ImportSkipReason.Item to 0),
            "negative" to importSkips(ImportSkipReason.Item to -4),
        ).forEach { (name, skips) ->
            assertTrue(skips.isEmpty, name)
            assertEquals(0, skips.totalCount, name)
            assertTrue(skips.counted.isEmpty(), name)
            assertEquals(importSkips(), skips, name)
        }
    }

    @Test
    fun `two tallies that counted the same things are equal however they were built`() {
        val built = importSkips() + ImportSkipReason.Item + ImportSkipReason.Item
        assertEquals(importSkips(ImportSkipReason.Item to 2), built)
        assertEquals(importSkips(ImportSkipReason.Item to 2).hashCode(), built.hashCode())
    }

    @Test
    fun `merging adds per reason`() {
        val left = importSkips(
            ImportSkipReason.Passkey to 1,
            ImportSkipReason.Item to 1,
        )
        val right = importSkips(
            ImportSkipReason.Item to 2,
            ImportSkipReason.Account to 1,
        )
        assertEquals(
            importSkips(
                ImportSkipReason.Passkey to 1,
                ImportSkipReason.Item to 3,
                ImportSkipReason.Account to 1,
            ),
            left + right,
        )
        // Merging the empty tally is a no-op in both directions.
        assertEquals(left, left + importSkips())
        assertEquals(left, importSkips() + left)
    }

    @Test
    fun `counted is declaration order and omits zeros`() {
        val skips = importSkips(
            ImportSkipReason.Account to 1,
            ImportSkipReason.Item to 2,
            ImportSkipReason.Otp to 0,
            ImportSkipReason.Passkey to 3,
        )
        assertEquals(
            listOf(
                ImportSkipReason.Passkey,
                ImportSkipReason.Item,
                ImportSkipReason.Account,
            ),
            skips.counted.map { it.first },
        )
        assertEquals(listOf(3, 2, 1), skips.counted.map { it.second })
    }

    @Test
    fun `titles do not affect equality or hash`() {
        // The premise of the whole title feature: the count is the tally's
        // identity and a name is an annotation over it. If this ever stops
        // holding, ~97 whole-tally expectations across the mapper matrices and
        // the round-trip harness have to start restating names no rule depends
        // on — so this is the test that keeps that from happening quietly.
        val plain = importSkips(ImportSkipReason.Item to 2)
        val titled = plain.titled("Netflix")
        assertEquals(plain, titled)
        assertEquals(plain.hashCode(), titled.hashCode())
        assertEquals(titled, titled.titled("GitHub"))
    }

    @Test
    fun `titling attributes every counted reason and leaves the counts alone`() {
        val skips = importSkips(
            ImportSkipReason.Passkey to 1,
            ImportSkipReason.Otp to 3,
        ).titled("Netflix")
        assertEquals(mapOf("Netflix" to 1), skips.titlesOf(ImportSkipReason.Passkey))
        assertEquals(mapOf("Netflix" to 3), skips.titlesOf(ImportSkipReason.Otp))
        // A reason that never fired gains nothing to show.
        assertEquals(emptyMap(), skips.titlesOf(ImportSkipReason.Item))
        assertEquals(1, skips[ImportSkipReason.Passkey])
        assertEquals(3, skips[ImportSkipReason.Otp])
    }

    @Test
    fun `merging sums titles per reason and item`() {
        val left = importSkips(ImportSkipReason.Otp to 1).titled("Netflix")
        val right = importSkips(ImportSkipReason.Otp to 2).titled("Netflix")
        val other = importSkips(ImportSkipReason.Otp to 1).titled("GitHub")
        val merged = left + right + other
        // The same item losing the same kind of thing three times is one row
        // saying three, not three rows.
        assertEquals(
            mapOf("Netflix" to 3, "GitHub" to 1),
            merged.titlesOf(ImportSkipReason.Otp),
        )
        assertEquals(4, merged[ImportSkipReason.Otp])
    }

    @Test
    fun `an unattributed loss stays counted but unnamed`() {
        // The honesty guarantee: reasons that fire on unreadable input have no
        // title to give, and a blank one is not a name. The count must survive
        // both, because it is the only authority on how much was lost.
        val blank = importSkips(ImportSkipReason.Item to 1).titled("   ")
        assertEquals(emptyMap(), blank.titlesOf(ImportSkipReason.Item))
        assertEquals(1, blank[ImportSkipReason.Item])

        val partial = importSkips(ImportSkipReason.Item to 1).titled("Netflix") +
                importSkips(ImportSkipReason.Item to 1)
        assertEquals(2, partial[ImportSkipReason.Item])
        assertEquals(
            1,
            partial.titlesOf(ImportSkipReason.Item).values.sum(),
            "attributed titles must never exceed the count",
        )
    }

    @Test
    fun `an import tally never equals an export tally`() {
        // The two sides are typealiases of one class, so only the stored reason
        // type keeps an `assertEquals` that crossed them from passing silently.
        assertNotEquals<Any?>(importSkips(), cxfExportSkips())
        assertNotEquals<Any?>(
            importSkips(ImportSkipReason.Passkey to 1),
            cxfExportSkips(CxfExportSkipReason.Passkey to 1),
        )
    }
}
