package com.artemchep.keyguard.common.service.credentialexchange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The tally's own contract. Everything the rest of the credential-exchange code
 * assumes about [CxfSkips] — that the total is derived, that a non-positive
 * count is never stored, that two tallies built differently but counting the
 * same things are equal, and that the two directions never compare equal — is
 * asserted here rather than left to the reader.
 */
class CxfSkipsTest {
    @Test
    fun `the total is the sum of what was counted`() {
        val skips = cxfImportSkips(
            CxfImportSkipReason.Passkey to 2,
            CxfImportSkipReason.Item to 3,
        )
        assertEquals(5, skips.totalCount)
        assertEquals(2, skips[CxfImportSkipReason.Passkey])
        assertEquals(3, skips[CxfImportSkipReason.Item])
        assertEquals(0, skips[CxfImportSkipReason.Account])
    }

    @Test
    fun `a non-positive count is not stored`() {
        listOf(
            "zero" to cxfImportSkips(CxfImportSkipReason.Item to 0),
            "negative" to cxfImportSkips(CxfImportSkipReason.Item to -4),
        ).forEach { (name, skips) ->
            assertTrue(skips.isEmpty, name)
            assertEquals(0, skips.totalCount, name)
            assertTrue(skips.counted.isEmpty(), name)
            assertEquals(cxfImportSkips(), skips, name)
        }
    }

    @Test
    fun `two tallies that counted the same things are equal however they were built`() {
        val built = cxfImportSkips() + CxfImportSkipReason.Item + CxfImportSkipReason.Item
        assertEquals(cxfImportSkips(CxfImportSkipReason.Item to 2), built)
        assertEquals(cxfImportSkips(CxfImportSkipReason.Item to 2).hashCode(), built.hashCode())
    }

    @Test
    fun `merging adds per reason`() {
        val left = cxfImportSkips(
            CxfImportSkipReason.Passkey to 1,
            CxfImportSkipReason.Item to 1,
        )
        val right = cxfImportSkips(
            CxfImportSkipReason.Item to 2,
            CxfImportSkipReason.Account to 1,
        )
        assertEquals(
            cxfImportSkips(
                CxfImportSkipReason.Passkey to 1,
                CxfImportSkipReason.Item to 3,
                CxfImportSkipReason.Account to 1,
            ),
            left + right,
        )
        // Merging the empty tally is a no-op in both directions.
        assertEquals(left, left + cxfImportSkips())
        assertEquals(left, cxfImportSkips() + left)
    }

    @Test
    fun `counted is declaration order and omits zeros`() {
        val skips = cxfImportSkips(
            CxfImportSkipReason.Account to 1,
            CxfImportSkipReason.Item to 2,
            CxfImportSkipReason.Otp to 0,
            CxfImportSkipReason.Passkey to 3,
        )
        assertEquals(
            listOf(
                CxfImportSkipReason.Passkey,
                CxfImportSkipReason.Item,
                CxfImportSkipReason.Account,
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
        val plain = cxfImportSkips(CxfImportSkipReason.Item to 2)
        val titled = plain.titled("Netflix")
        assertEquals(plain, titled)
        assertEquals(plain.hashCode(), titled.hashCode())
        assertEquals(titled, titled.titled("GitHub"))
    }

    @Test
    fun `titling attributes every counted reason and leaves the counts alone`() {
        val skips = cxfImportSkips(
            CxfImportSkipReason.Passkey to 1,
            CxfImportSkipReason.Otp to 3,
        ).titled("Netflix")
        assertEquals(mapOf("Netflix" to 1), skips.titlesOf(CxfImportSkipReason.Passkey))
        assertEquals(mapOf("Netflix" to 3), skips.titlesOf(CxfImportSkipReason.Otp))
        // A reason that never fired gains nothing to show.
        assertEquals(emptyMap(), skips.titlesOf(CxfImportSkipReason.Item))
        assertEquals(1, skips[CxfImportSkipReason.Passkey])
        assertEquals(3, skips[CxfImportSkipReason.Otp])
    }

    @Test
    fun `merging sums titles per reason and item`() {
        val left = cxfImportSkips(CxfImportSkipReason.Otp to 1).titled("Netflix")
        val right = cxfImportSkips(CxfImportSkipReason.Otp to 2).titled("Netflix")
        val other = cxfImportSkips(CxfImportSkipReason.Otp to 1).titled("GitHub")
        val merged = left + right + other
        // The same item losing the same kind of thing three times is one row
        // saying three, not three rows.
        assertEquals(
            mapOf("Netflix" to 3, "GitHub" to 1),
            merged.titlesOf(CxfImportSkipReason.Otp),
        )
        assertEquals(4, merged[CxfImportSkipReason.Otp])
    }

    @Test
    fun `an unattributed loss stays counted but unnamed`() {
        // The honesty guarantee: reasons that fire on unreadable input have no
        // title to give, and a blank one is not a name. The count must survive
        // both, because it is the only authority on how much was lost.
        val blank = cxfImportSkips(CxfImportSkipReason.Item to 1).titled("   ")
        assertEquals(emptyMap(), blank.titlesOf(CxfImportSkipReason.Item))
        assertEquals(1, blank[CxfImportSkipReason.Item])

        val partial = cxfImportSkips(CxfImportSkipReason.Item to 1).titled("Netflix") +
                cxfImportSkips(CxfImportSkipReason.Item to 1)
        assertEquals(2, partial[CxfImportSkipReason.Item])
        assertEquals(
            1,
            partial.titlesOf(CxfImportSkipReason.Item).values.sum(),
            "attributed titles must never exceed the count",
        )
    }

    @Test
    fun `an import tally never equals an export tally`() {
        // The two sides are typealiases of one class, so only the stored reason
        // type keeps an `assertEquals` that crossed them from passing silently.
        assertNotEquals<Any?>(cxfImportSkips(), cxfExportSkips())
        assertNotEquals<Any?>(
            cxfImportSkips(CxfImportSkipReason.Passkey to 1),
            cxfExportSkips(CxfExportSkipReason.Passkey to 1),
        )
    }
}
