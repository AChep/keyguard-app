package app.keemobile.kotpass.database

import app.keemobile.kotpass.models.BinaryData
import kotlin.test.Test
import kotlin.test.assertEquals

class BinaryWritePlanTest {
    @Test
    fun normalizesSparseAndDuplicateInputReferences() {
        val first = BinaryData.Uncompressed(false, "first".encodeToByteArray())
        val second = BinaryData.Uncompressed(false, "second".encodeToByteArray())
        val binaries = BinaryPool().apply {
            add(0, first)
            add(2, second)
            add(7, second)
        }

        val plan = BinaryWritePlan.create(binaries)

        assertEquals(listOf(0, 1), plan.entries.map { it.ref })
        assertEquals(listOf(first.hash, second.hash), plan.entries.map { it.hash })
        assertEquals(0, plan.refByHash(first.hash))
        assertEquals(1, plan.refByHash(second.hash))
    }
}
