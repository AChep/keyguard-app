package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TEST ONLY: the records of one kind on a test device, for [AppSyncRowContractTest]. A
 * record is told by its name; a variant, from 0 to 2, picks the values of its other fields.
 */
internal interface AppSyncTestRows {
    val kind: EntryKind

    /** Adds the record [name] with the fields of the [variant]. */
    suspend fun add(device: AppLinkTestDevice, name: String, variant: Int = 0)

    /** Renames the record [name] to [newName], and gives it the fields of the [variant]. */
    suspend fun edit(device: AppLinkTestDevice, name: String, newName: String, variant: Int)

    suspend fun remove(device: AppLinkTestDevice, name: String)

    /** What syncs of each record the [device] holds: its fields but the local ones, in order. */
    suspend fun synced(device: AppLinkTestDevice): List<String>

    /** The id of the local row of the record [name]. */
    suspend fun rowId(device: AppLinkTestDevice, name: String): String

    /** The sync id of the record [name], or `null` if it never syncs. */
    suspend fun syncId(device: AppLinkTestDevice, name: String): String?
}

/**
 * What linked apps do with the records of every kind whose record is one row, see
 * [writeRow]. The test of a kind extends it with the [rows] of the kind, and adds what
 * only that kind does.
 */
abstract class AppSyncRowContractTest internal constructor(
    private val rows: AppSyncTestRows,
) {
    private companion object {
        const val FIRST = "com.example.first"
        const val SECOND = "org.example.second"
    }

    @Test
    fun `linked apps sync their records both ways`() = runTest {
        val (a, b) = linkedPair {
            rows.add(a, FIRST, variant = 1)
        }
        rows.add(b, SECOND, variant = 2)
        val expected = (rows.synced(a) + rows.synced(b)).distinct().sorted()
        a.exchangeWith(b)

        assertEquals(expected, rows.synced(a))
        assertEquals(expected, rows.synced(b))
        assertNotNull(rows.syncId(a, FIRST))
        assertEquals(rows.syncId(a, FIRST), rows.syncId(b, FIRST))
        assertEquals(rows.syncId(a, SECOND), rows.syncId(b, SECOND))
    }

    @Test
    fun `an edit updates the record in place and a delete removes it`() = runTest {
        val (a, b) = linkedPair {}
        // What a device holds of the kind by itself, such as a built-in record.
        val empty = rows.synced(b)
        rows.add(a, FIRST, variant = 1)
        a.exchangeWith(b)
        val rowId = rows.rowId(b, FIRST)

        a.time += 1_000
        rows.edit(a, FIRST, SECOND, variant = 2)
        a.exchangeWith(b)

        assertEquals(rows.synced(a), rows.synced(b))
        // In place: the app may refer to a record by its row.
        assertEquals(rowId, rows.rowId(b, SECOND))

        a.time += 1_000
        rows.remove(a, SECOND)
        a.exchangeWith(b)

        assertEquals(empty, rows.synced(b))
    }

    @Test
    fun `linking identical records adopts one id and keeps the local rows`() = runTest {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        rows.add(a, FIRST)
        rows.add(b, FIRST)
        val before = rows.synced(b)
        val rowId = rows.rowId(b, FIRST)

        a.link()
        b.time = a.time + 1
        b.join(a)
        a.exchangeWith(b)

        // Neither device holds the record twice.
        assertEquals(before, rows.synced(a))
        assertEquals(before, rows.synced(b))
        assertEquals(rows.syncId(a, FIRST), rows.syncId(b, FIRST))
        assertEquals(rowId, rows.rowId(b, FIRST))
    }

    @Test
    fun `a local change is recorded as it is made`() = runTest {
        val (a) = linkedPair {}
        rows.add(a, FIRST)

        val entry = a.entriesOf(rows.kind).single()
        assertEquals(rows.syncId(a, FIRST), entry.id)
        assertFalse(entry.deleted)

        a.time += 1_000
        rows.remove(a, FIRST)
        assertTrue(a.entriesOf(rows.kind).single().deleted)
    }
}
