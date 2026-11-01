package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException

class ImportPlanBuilderTest {
    @Test
    fun `a failed record replaces its partial skips with one item skip`() {
        val builder = ImportPlanBuilder(ImportPlan.Source())
        builder.record("Mail") {
            builder.skip(ImportSkipReason.Attachment, "Mail", count = 2)
        }
        builder.record("Mail") {
            builder.skip(ImportSkipReason.Attachment, "Mail", count = 3)
            builder.skip(ImportSkipReason.Passkey, "Mail")
            builder.skip(ImportSkipReason.Item, "Mail")
            error("Cannot decode the rest of the record")
        }
        builder.record("Bank") {
            builder.skip(ImportSkipReason.Passkey, "Bank")
        }

        val tally = builder.build().skips
        assertEquals(
            importSkips(ImportSkipReason.Attachment to 2, ImportSkipReason.Passkey to 1, ImportSkipReason.Item to 1),
            tally,
        )
        assertEquals(mapOf("Mail" to 2), tally.titlesOf(ImportSkipReason.Attachment))
        assertEquals(mapOf("Mail" to 1), tally.titlesOf(ImportSkipReason.Item))
        assertEquals(mapOf("Bank" to 1), tally.titlesOf(ImportSkipReason.Passkey))
    }

    @Test
    fun `cancellation and fatal errors propagate and restore the parent tally`() {
        for (failure in listOf(CancellationException("Cancelled"), AssertionError("Fatal"))) {
            val builder = ImportPlanBuilder(ImportPlan.Source())
            builder.skip(ImportSkipReason.Attachment, "Earlier")
            val thrown = assertFailsWith<Throwable> {
                builder.record("Mail") {
                    builder.skip(ImportSkipReason.Otp, "Mail")
                    throw failure
                }
            }
            assertSame(failure, thrown)
            builder.skip(ImportSkipReason.Attachment, "Later")

            val tally = builder.build().skips
            assertEquals(importSkips(ImportSkipReason.Attachment to 2), tally)
            assertEquals(mapOf("Earlier" to 1, "Later" to 1), tally.titlesOf(ImportSkipReason.Attachment))
            assertEquals(emptyMap(), tally.titlesOf(ImportSkipReason.Otp))
        }
    }

    @Test
    fun `a non-local return commits skips and restores the parent tally`() {
        val builder = ImportPlanBuilder(ImportPlan.Source())
        fun addRecord() {
            builder.record("Mail") {
                builder.skip(ImportSkipReason.Otp, "Mail")
                return
            }
        }
        addRecord()
        builder.skip(ImportSkipReason.Attachment, "Bank")

        val tally = builder.build().skips
        assertEquals(importSkips(ImportSkipReason.Otp to 1, ImportSkipReason.Attachment to 1), tally)
        assertEquals(mapOf("Mail" to 1), tally.titlesOf(ImportSkipReason.Otp))
        assertEquals(mapOf("Bank" to 1), tally.titlesOf(ImportSkipReason.Attachment))
    }

    @Test
    fun `many distinct skipped titles survive in independent plan snapshots`() {
        val builder = ImportPlanBuilder(ImportPlan.Source())
        val itemCount = 30_000
        repeat(itemCount) { index ->
            builder.record("Item $index") {
                builder.skip(ImportSkipReason.PasswordHistory, "Item $index", count = 2)
                builder.skip(ImportSkipReason.Otp, "Item $index")
            }
        }
        val first = builder.build()
        builder.skip(ImportSkipReason.PasswordHistory, "Item 0")
        val second = builder.build()

        assertEquals(itemCount * 3, first.skips.totalCount)
        assertEquals(itemCount * 2, first.skips[ImportSkipReason.PasswordHistory])
        assertEquals(itemCount, first.skips[ImportSkipReason.Otp])
        assertEquals(
            (0 until itemCount).associate { "Item $it" to 2 },
            first.skips.titlesOf(ImportSkipReason.PasswordHistory),
        )
        assertEquals(
            (0 until itemCount).associate { "Item $it" to 1 },
            first.skips.titlesOf(ImportSkipReason.Otp),
        )
        assertEquals(itemCount * 3 + 1, second.skips.totalCount)
        assertEquals(3, second.skips.titlesOf(ImportSkipReason.PasswordHistory)["Item 0"])
    }
}
