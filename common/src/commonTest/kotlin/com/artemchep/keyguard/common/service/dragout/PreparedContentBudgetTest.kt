package com.artemchep.keyguard.common.service.dragout

import com.artemchep.keyguard.common.service.download.DownloadSnapshotWriter
import com.artemchep.keyguard.common.service.download.writeBytes
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.usecase.PrepareAttachmentContent
import com.artemchep.keyguard.test.MemoryStagingSpoolFactory
import com.artemchep.keyguard.test.TestByteSnapshot
import com.artemchep.keyguard.test.testPreparedContentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedContentBudgetTest {
    @Test
    fun `requested byte cap clamps upfront allowance and actual size releases unused reservation`() = runTest {
        val store = store()
        val reservation = store.reserve(maximumBytes = 6)
        assertEquals(6L, reservation.limits.maximumBytes)
        assertEquals(6L, reservation.limits.memoryBytes)
        assertFailsWith<IllegalStateException> { store.reserve(maximumBytes = 1) }
        val content = reservation.publish("file", snapshot(3))
        store.reserve(maximumBytes = 4).use {
            assertEquals(4L, it.limits.maximumBytes)
            assertEquals(4L, it.limits.memoryBytes)
        }
        store.reserve().use { assertEquals(9L, it.limits.maximumBytes) }
        content.close()
        runCurrent()
        store.reserve(maximumBytes = Long.MAX_VALUE).use { assertEquals(12L, it.limits.maximumBytes) }
    }

    @Test
    fun `nonpositive optional byte caps are rejected before taking preparation ownership`() = runTest {
        val store = store()
        assertFailsWith<IllegalArgumentException> { store.reserve(maximumBytes = 0) }
        assertFailsWith<IllegalArgumentException> { store.reserve(maximumBytes = -1) }
        val request = PrepareAttachmentContent.Request(
            attachment = DownloadAttachmentRequest.ByLocalCipherAttachment("cipher", "remote", "attachment"),
            name = "file",
        )
        assertFailsWith<IllegalArgumentException> { request.copy(maximumBytes = 0) }
        assertFailsWith<IllegalArgumentException> { request.copy(maximumBytes = -1) }
        store.reserve(maximumBytes = 1).close()
    }

    // DownloadSnapshotWriterTest covers rejecting a write past the limits.
    @Test
    fun `a reservation admits a write of exactly its allowance`() = runTest {
        store().reserve(maximumBytes = 8).use { reservation ->
            DownloadSnapshotWriter(MemoryStagingSpoolFactory(), reservation.limits).use { writer ->
                writer.writeBytes(ByteArray(8))
                writer.takeSnapshot().use { assertEquals(8L, it.size) }
            }
        }
    }

    private fun TestScope.store() = testPreparedContentStore(
        limits = PreparedContentLimits(maximumBytes = 12, memoryBytes = 8),
    )

    private fun snapshot(count: Int) = TestByteSnapshot(ByteArray(count))
}
