package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxDownload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Each sync group keeps its own partial download, see [KeyguardObjectDownloads]. */
class KeyguardObjectDownloadsTest {
    private val bytes = byteArrayOf(1, 2, 3, 4)

    /** A download of [item] cut short after [bytes]. */
    private fun partial(item: String) = RelayMailboxDownload(item).apply {
        etag = "\"18f2a3b4c5-a\""
        append(bytes, bytes.size)
    }

    @Test
    fun `a partial download of a group survives a fetch in another group`() {
        val downloads = KeyguardObjectDownloads()
        downloads.keep(groupId = "group-a", download = partial("a".repeat(64)))
        // Group B fetches another object, and cuts it short too.
        val other = downloads.take(groupId = "group-b", item = "b".repeat(64))
        assertEquals(0L, other.size)
        downloads.keep(groupId = "group-b", download = partial("b".repeat(64)))

        assertEquals("a".repeat(64), downloads.heldItem("group-a"))
        val resumed = downloads.take(groupId = "group-a", item = "a".repeat(64))
        assertEquals(bytes.size.toLong(), resumed.size)
        assertContentEquals(bytes, resumed.take())
    }
}
